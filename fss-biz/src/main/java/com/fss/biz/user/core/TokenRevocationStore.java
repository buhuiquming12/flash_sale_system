package com.fss.biz.user.core;

/**
 * JWT 主动失效存储。
 *
 * <p>JWT 是无状态的，签发后在过期前天然无法撤销。要支持"主动登出""踢下线"，
 * 必须在服务端保留一份 {@code jti} 的状态。
 *
 * <p>正常部署下由 {@link RedisTokenRevocationStore} 实现
 * （{@code jwt:active:{jti}} 白名单）；没有 Redis 时退化为
 * {@link NoopTokenRevocationStore}，此时登出只是客户端丢弃 token，
 * 服务端仍然认这个 token 直到过期。
 */
public interface TokenRevocationStore {

    /** 签发时登记 */
    void register(String jti, long ttlSeconds);

    /** 校验时判断是否仍然有效 */
    boolean isActive(String jti);

    /** 登出 / 踢下线 */
    void revoke(String jti);
}
