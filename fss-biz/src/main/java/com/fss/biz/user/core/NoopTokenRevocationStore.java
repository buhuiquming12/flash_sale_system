package com.fss.biz.user.core;

/**
 * 没有 Redis 时的降级实现：不做撤销，一律视为有效。
 *
 * <p>此时"登出"只是客户端丢弃 token，服务端仍然认这个 token 直到过期。
 * 这是<b>已知限制</b>，正常部署下由 {@link RedisTokenRevocationStore} 接管。
 *
 * <p>装配方式见 {@link TokenRevocationConfig}——注意它不是 {@code @Component}。
 */
public class NoopTokenRevocationStore implements TokenRevocationStore {

    @Override
    public void register(String jti, long ttlSeconds) {
        // 无状态，无需登记
    }

    @Override
    public boolean isActive(String jti) {
        return true;
    }

    @Override
    public void revoke(String jti) {
        // 无 Redis 时无法撤销，见类注释中的已知限制
    }
}
