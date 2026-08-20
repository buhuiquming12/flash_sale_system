package com.fss.biz.user.core;

import com.fss.infra.redis.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 基于 Redis 的 JWT 主动失效存储（阶段二）。
 *
 * <p><b>白名单而不是黑名单</b>：这里存的是"仍然有效的 jti"（{@code jwt:active:{jti}}），
 * 登出就是删掉这个 key。反过来做黑名单（存已撤销的 jti）在功能上也能实现登出，
 * 但少了一样东西：<b>撤销全部历史 token 的能力</b>。
 * 白名单模式下 {@code FLUSH} 掉这个前缀就能强制全站重新登录（密钥泄露时的应急手段），
 * 黑名单模式下你根本不知道有哪些 token 在外面流通。
 *
 * <p>代价是每次校验都要读一次 Redis。这条读走的是本地网络、O(1) 的 EXISTS，
 * 且秒杀链路上本来就要访问 Redis，多一次不改变数量级。
 *
 * <p><b>Redis 不可用时的失败方向：fail-open（放行）。</b>
 * 这是一个需要明说的取舍：Redis 挂了如果一律拒绝，等于所有已登录用户被踢下线，
 * 秒杀活动直接停摆——把缓存故障放大成全站不可用。放行的风险是"已登出的 token
 * 在 Redis 故障期间又能用了"，窗口等于故障时长，且 token 本身仍有签名与过期时间保护。
 * 权衡之下放行更合理。这与库存判定的 fail-closed 正好相反，因为两者的最坏后果
 * 不在一个量级：一个是"少数已登出会话短暂复活"，另一个是超卖导致的真实资损。
 *
 * <p>装配见 {@link TokenRevocationConfig}——注意它不是 {@code @Component}，
 * 由配置类显式二选一，不依赖任何条件注解的求值时机。
 */
@Slf4j
public class RedisTokenRevocationStore implements TokenRevocationStore {

    private static final String VALUE = "1";

    private final StringRedisTemplate redis;

    public RedisTokenRevocationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void register(String jti, long ttlSeconds) {
        try {
            // TTL 与 token 自身的过期时间一致：token 过期后这个 key 再留着毫无意义，
            // 只会无限堆积。不设 TTL 是这类实现最常见的内存泄漏
            redis.opsForValue().set(RedisKeys.jwtActive(jti), VALUE,
                    Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            // 登记失败不能让登录失败：此时 isActive 会因为 key 不存在而返回 false，
            // 用户拿到一个立刻失效的 token —— 所以这里必须记 error 让人看见
            log.error("JWT 登记失败 jti={}，该 token 可能无法通过校验", jti, e);
        }
    }

    @Override
    public boolean isActive(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;                    // 没有 jti 的 token 是我们没签发过的
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(RedisKeys.jwtActive(jti)));
        } catch (Exception e) {
            log.warn("JWT 有效性检查失败，放行 jti={}", jti, e);
            return true;                     // fail-open，理由见类注释
        }
    }

    @Override
    public void revoke(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        try {
            redis.delete(RedisKeys.jwtActive(jti));
        } catch (Exception e) {
            // 撤销失败必须让调用方知道：用户点了"退出登录"却没真的退出，
            // 静默失败在共享设备场景下是安全问题
            log.error("JWT 撤销失败 jti={}，token 在过期前仍然有效", jti, e);
            throw e;
        }
    }
}
