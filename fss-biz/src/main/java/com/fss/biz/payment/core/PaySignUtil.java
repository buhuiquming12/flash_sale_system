package com.fss.biz.payment.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 回调验签。{@code HMAC-SHA256(按 key 升序拼接的 kv 串, secret)}。
 */
public final class PaySignUtil {

    /** 与 {@code JwtService} 对 JWT 密钥的要求保持一致 */
    private static final int MIN_SECRET_LENGTH = 32;

    private PaySignUtil() {
    }

    public static String sign(Map<String, String> params, String secret) {
        // TreeMap 保证 key 升序；排除 sign 自身和空值，与渠道侧约定一致
        String content = new TreeMap<>(params).entrySet().stream()
                .filter(e -> !"sign".equals(e.getKey()))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return hmacSha256(content, secret);
    }

    /**
     * 定时安全比较。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
     * 后者短路返回，比较耗时随匹配前缀长度变化，理论上可被用来逐字节猜签名。
     */
    public static boolean verify(Map<String, String> params, String secret, String provided) {
        if (provided == null || provided.isEmpty()) {
            return false;
        }
        String expected = sign(params, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256(String content, String secret) {
        // 密钥过短会让 HMAC 的强度形同虚设，而伪造回调的后果是用户不付钱就能把订单
        // 置为已支付——直接资损。这里与 JwtService 对称地校验，不放任 null 走到
        // SecretKeySpec 才炸出一句看不出所以然的异常
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("fss.pay.notify-secret 至少需要 "
                    + MIN_SECRET_LENGTH + " 字节，当前 "
                    + (secret == null ? "未配置" : secret.length() + " 字节"));
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }
}
