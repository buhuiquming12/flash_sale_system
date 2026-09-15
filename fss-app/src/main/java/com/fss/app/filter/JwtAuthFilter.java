package com.fss.app.filter;

import com.fss.biz.user.core.JwtService;
import com.fss.common.context.UserContext;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.web.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>白名单之外的请求必须携带有效 token。校验通过后写入 {@link UserContext}，
 * <b>finally 中必须清除</b>——Tomcat 复用线程，不清除会让下一个请求继承上一个
 * 用户的身份，这是最危险的一类越权。
 *
 * <p>活动详情是唯一的例外：匿名可读，且<b>即使带了 token 也不解析</b>。
 * 原先带了 token 会解析出身份，但那条路径上没有任何消费方读它
 * （{@code ActivityController} 不碰 {@link UserContext}，限流拦截器对
 * {@code /api/activity/} 也不做用户级分桶），解析一次只是白花一次 Redis 查询。
 * 将来要做"已抢过"这类个性化展示时，在 {@link #ANONYMOUS_PREFIX} 那个分支里
 * 补回 {@code verify} + {@code set} 即可。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    /**
     * 匿名可读的路径前缀：不要求 token，也不解析 token。
     *
     * <p>单独一个常量而不是塞进 {@link #WHITELIST}，因为两者语义不同：白名单是
     * "整个过滤器跳过"，这里是"过滤器照常跑，只是这条路不要求认证"。
     * 当前这个前缀下只有 {@code /api/activity/list} 与 {@code /api/activity/{id}}
     * 两个公开读接口，都在 {@code ActivityController} 里。
     *
     * <p><b>加路由前先看这里。</b> 前缀匹配不看 HTTP 方法，也不区分是哪个处理器：
     * 在 {@code /api/activity/} 下新增的<b>任何</b>路由——包括将来可能出现的
     * 写接口——都会自动匿名，且不会有任何报错提醒。确实要加公开读接口时没问题；
     * 加的是需要鉴权的接口，就得先把这个前缀收窄成 {@link #WHITELIST} 那样的精确清单。
     */
    private static final String ANONYMOUS_PREFIX = "/api/activity/";

    /** 无需认证的路径前缀 */
    private static final List<String> WHITELIST = List.of(
            "/api/user/register",
            "/api/user/login",
            "/api/pay/notify",          // 渠道回调走验签，不走 JWT
            "/api/pay/mock-sign",       // 演示用
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs",
            "/mock-pay.html",
            "/index.html",
            "/favicon.ico");

    private final JwtService jwtService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("/".equals(uri) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return WHITELIST.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        // 匿名路径直接放行，既不要求 token 也不解析 token
        if (req.getRequestURI().startsWith(ANONYMOUS_PREFIX)) {
            chain.doFilter(req, resp);
            return;
        }

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            reject(resp, ErrorCode.UNAUTHORIZED);
            return;
        }

        try {
            JwtService.Payload p = jwtService.verify(header.substring(BEARER.length()).trim());
            UserContext.set(p.userId(), p.username(), p.role());
        } catch (BizException e) {
            reject(resp, e.getErrorCode());
            return;
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            UserContext.clear();
        }
    }

    /** 除 FORBIDDEN 外一律 401——过滤器的拒绝只有"没登录"和"不够权限"两种 */
    private void reject(HttpServletResponse resp, ErrorCode ec) throws IOException {
        ErrorResponseWriter.write(resp,
                ec == ErrorCode.FORBIDDEN ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED, ec);
    }
}
