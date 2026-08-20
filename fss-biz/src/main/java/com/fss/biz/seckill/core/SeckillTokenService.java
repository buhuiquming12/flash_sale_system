package com.fss.biz.seckill.core;

import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.util.IdGenerator;
import com.fss.infra.config.FssProperties;
import com.fss.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 秒杀令牌与动态路径。
 *
 * <p><b>先说清它防不住什么</b>：令牌只提高脚本作者的成本——他必须先调令牌接口、
 * 必须从响应里解析出动态路径，不能把秒杀 URL 硬编码进脚本。但拿到令牌之后，
 * 对方照样能高频提交。真正的防线是限流和一人一单，令牌只是抬高门槛。
 * 把它当成安全措施会导致真正的防护被忽视，这一点必须写在文档里而不是留给读者猜。
 *
 * <p>它确实解决的问题是"活动 URL 提前泄露"：路径里的那一段由令牌决定，
 * 活动开始前拿不到令牌就拼不出路径，防不住有心人，但能挡住把 URL 抄下来
 * 提前定时刷的脚本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillTokenService {

    private final StringRedisTemplate redis;
    private final FssProperties       props;

    /** 签发令牌，返回令牌串。客户端据此拼出 {@code /api/seckill/{token}/do} */
    public String issue(long userId, long activityId, long skuId) {
        String token = sha256Hex(userId + ":" + activityId + ":" + skuId + ":"
                + IdGenerator.nonce() + ":" + System.nanoTime());
        redis.opsForValue().set(RedisKeys.seckillToken(userId, activityId, skuId),
                token, props.getSeckill().getTokenTtl());
        return token;
    }

    /**
     * 校验并消费令牌。
     *
     * <p>{@code getAndDelete}（Redis 6.2 起的 {@code GETDEL}）保证令牌只能用一次。
     * 写成"先 GET 比对、再 DELETE"会留一个并发窗口：脚本用同一个令牌并发打 100 次，
     * 全部都能在 DELETE 之前通过 GET 校验，令牌的一次性就完全失效了。
     *
     * <p><b>副作用：令牌串不匹配时令牌也被消费掉了。</b> GETDEL 没有"比对不上就别删"
     * 这个选项，这是接受它的原子性所付的代价。影响范围是可控的——key 由已认证的
     * {@code userId} 推出来，攻击者只能作废自己的令牌，作废不了别人的；
     * 受影响的用户重新领一次即可。用一个并发漏洞去换"带错令牌时不必重领"，
     * 不划算。
     */
    public void verifyAndConsume(String token, long userId, long activityId, long skuId) {
        String key = RedisKeys.seckillToken(userId, activityId, skuId);
        String stored;
        try {
            stored = redis.opsForValue().getAndDelete(key);
        } catch (Exception e) {
            // 令牌校验的 Redis 挂了：这里选择 fail-closed（拒绝）而不是放行。
            // 令牌不是限流，它是"这次提交是否走过正规流程"的凭据；
            // 放行意味着任何人直接 POST 到动态路径都能通过，令牌形同不存在
            log.error("令牌校验失败（Redis 异常）userId={} activityId={}", userId, activityId, e);
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        }
        if (stored == null || !MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ErrorCode.SECKILL_TOKEN_INVALID);
        }
    }

    /** 客户端应当提交到的动态路径 */
    public String dynamicPath(String token) {
        return "/api/seckill/" + token + "/do";
    }

    /** 定长比较用的十六进制摘要。用 MessageDigest.isEqual 比对，避免计时侧信道 */
    private static String sha256Hex(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                  .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE 必须支持 SHA-256", e);
        }
    }
}
