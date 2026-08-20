package com.fss.biz.user.core;

import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.util.IdGenerator;
import com.fss.infra.config.FssProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。
 *
 * <p>payload 只含 {@code userId / username / role / jti / exp}，
 * <b>不含手机号等敏感信息</b>——JWT 只是 base64 编码，不是加密，任何持有 token
 * 的人都能读出 payload。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtService {

    private final FssProperties         props;
    private final TokenRevocationStore  revocationStore;

    private SecretKey key;

    private SecretKey key() {
        if (key == null) {
            byte[] raw = props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
            if (raw.length < 32) {
                // HMAC-SHA256 要求密钥至少 256 位，短密钥会让签名强度形同虚设
                throw new IllegalStateException(
                        "fss.jwt.secret 至少需要 32 字节，当前 " + raw.length);
            }
            key = new SecretKeySpec(raw, "HmacSHA256");
        }
        return key;
    }

    public Issued issue(long userId, String username, int role) {
        long ttlSeconds = props.getJwt().getTtl().toSeconds();
        String jti = IdGenerator.nonce();
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlSeconds * 1000);

        String token = Jwts.builder()
                .issuer(props.getJwt().getIssuer())
                .subject(String.valueOf(userId))
                .id(jti)
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key())
                .compact();

        revocationStore.register(jti, ttlSeconds);
        return new Issued(token, ttlSeconds, jti);
    }

    /** 校验失败一律抛 UNAUTHORIZED，不区分"签名错误""已过期""已登出"——不给攻击者信息 */
    public Payload verify(String token) {
        try {
            Claims c = Jwts.parser()
                    .verifyWith(key())
                    .requireIssuer(props.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!revocationStore.isActive(c.getId())) {
                throw new BizException(ErrorCode.UNAUTHORIZED);
            }
            Integer role = c.get("role", Integer.class);
            return new Payload(Long.parseLong(c.getSubject()),
                    c.get("username", String.class),
                    role == null ? 0 : role,
                    c.getId());
        } catch (BizException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("JWT 校验失败: {}", e.getMessage());
            }
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    public void revoke(String jti) {
        revocationStore.revoke(jti);
    }

    public record Issued(String token, long expiresIn, String jti) {
    }

    public record Payload(long userId, String username, int role, String jti) {
    }
}
