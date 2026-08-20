package com.fss.app.interceptor;

import com.fss.common.context.UserContext;
import com.fss.common.error.ErrorCode;
import com.fss.common.result.R;
import com.fss.common.util.IpUtil;
import com.fss.common.util.JsonUtil;
import com.fss.infra.config.FssProperties;
import com.fss.infra.ratelimit.RateLimiter;
import com.fss.infra.redis.RedisKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Redis 令牌桶限流拦截器（四级限流的第三级）。
 *
 * <p>顺序上<b>先查 IP 再查用户再查活动</b>，不是随便排的：
 * IP 桶的 key 数量远少于用户桶（一个 IP 对应很多用户），命中拒绝的概率更高，
 * 早一步拒绝就能省掉后面两次 Redis 往返。
 *
 * <p><b>为什么用 HandlerInterceptor 而不是 Filter</b>：限流要按 userId 分桶，
 * 而 userId 来自 {@code JwtAuthFilter} 填进 {@link UserContext} 的值。
 * 拦截器跑在所有 Filter 之后、Controller 之前，正好能拿到已认证的身份。
 * 写成 Filter 就得自己管顺序，还得复制一遍 token 解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter   rateLimiter;
    private final FssProperties props;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler)
            throws IOException {
        FssProperties.RateLimit rl = props.getRatelimit();
        if (!rl.isEnabled()) {
            return true;
        }
        int burst = Math.max(1, rl.getBurstFactor());

        String ip = IpUtil.realIp(req);
        if (!rateLimiter.tryAcquire(RedisKeys.rateIp(ip), rl.getIpQps() * burst, rl.getIpQps())) {
            return reject(resp, "IP 请求过于频繁", "ip=" + ip);
        }

        Long userId = UserContext.userIdOrNull();
        Long activityId = extractActivityId(req);

        // 用户桶的速率<b>按接口分档</b>，不能一把 2 QPS 卡住所有接口。
        // 单用户 2 QPS 是给"提交秒杀"定的——一个人一秒点两次已经不正常了。
        // 但同一次点击背后还有活动列表、活动详情、订单列表几个读请求，
        // 用同一个 2 QPS 的桶去卡，用户点一次就把配额吃光，第二次点击直接 429。
        // 读接口的防护交给 IP 桶和 Sentinel，它们的阈值是按读的量级定的
        int userQps = userQpsFor(req.getRequestURI(), rl);
        if (userId != null && userQps > 0) {
            // 桶按 (用户, 活动) 分：用户在 A 活动上被限速，不该影响他抢 B 活动。
            // activityId 拿不到时退化为按用户维度限速
            String key = RedisKeys.rateUser(userId, activityId == null ? 0L : activityId);
            if (!rateLimiter.tryAcquire(key, userQps * burst, userQps)) {
                return reject(resp, "操作过于频繁，请稍候再试",
                        "userId=" + userId + " activityId=" + activityId + " qps=" + userQps);
            }
        }

        if (activityId != null && !rateLimiter.tryAcquire(
                RedisKeys.rateActivity(activityId),
                rl.getActivityQps() * burst, rl.getActivityQps())) {
            return reject(resp, "活动太火爆，请稍后再试", "activityId=" + activityId);
        }
        return true;
    }

    /**
     * 该路径对应的单用户 QPS 档位，0 表示不做用户级限流。
     *
     * <p>按 URI 前缀而不是加注解：注解要在每个方法上标一遍，
     * 新加的秒杀接口一旦忘标就是完全没有用户级限流，而这种遗漏不会有任何报错。
     * 前缀匹配的默认是"不限"，新接口漏配最多是防护不足而不是行为错误，
     * 而 {@code /api/seckill/} 下的路径这里全都覆盖到了。
     */
    private int userQpsFor(String uri, FssProperties.RateLimit rl) {
        if (uri.startsWith("/api/seckill/result")) {
            return rl.getResultQps();
        }
        if (uri.startsWith("/api/seckill/")) {
            // 提交（含动态路径 /api/seckill/{token}/do）与领令牌都算"抢购动作"
            return rl.getUserQps();
        }
        return 0;
    }

    /**
     * 从请求里取 activityId。
     *
     * <p><b>只看查询参数，不读请求体。</b> Servlet 的输入流只能读一次，
     * 在拦截器里把 body 读掉之后 Spring 就拿不到参数了（除非套一层
     * ContentCachingRequestWrapper，那要为每个请求多复制一份 body，
     * 在秒杀入口上是纯粹的浪费）。
     *
     * <p>秒杀提交的 activityId 在请求体里，所以这一层对它退化为按用户限速；
     * 活动维度的限流由 Sentinel 的接口级 QPS 和 Nginx 兜住。
     * 想让活动桶对秒杀接口生效，正确做法是让客户端在查询串里也带上
     * {@code activityId}，而不是在这里解析 body。
     */
    private Long extractActivityId(HttpServletRequest req) {
        String v = req.getParameter("activityId");
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(v.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 429 + Retry-After。
     *
     * <p>直接写响应体而不是抛异常：拦截器抛出的异常虽然能被
     * {@code @RestControllerAdvice} 接住，但 {@code preHandle} 抛异常时
     * Spring 不会调用 {@code afterCompletion}，容易漏掉清理逻辑；
     * 而且限流是一条明确的"到此为止"，写完就返回 false 更直白。
     */
    private boolean reject(HttpServletResponse resp, String msg, String detail) throws IOException {
        log.debug("stage=RATE_LIMIT result=REJECT {}", detail);
        resp.setStatus(429);
        // 告诉客户端多久后再来，比让它立刻重试好——立刻重试只会让限流器更忙
        resp.setHeader("Retry-After", "1");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(R.fail(ErrorCode.RATE_LIMITED, msg)));
        return false;
    }
}
