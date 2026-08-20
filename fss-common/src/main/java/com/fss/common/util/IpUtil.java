package com.fss.common.util;

import jakarta.servlet.http.HttpServletRequest;

/** 客户端真实 IP 提取。 */
public final class IpUtil {

    private static final String XFF = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";

    private IpUtil() {
    }

    /**
     * 取客户端 IP。
     *
     * <p><b>关键点：取 XFF 的最右侧一段，不是最左侧。</b>
     * XFF 是逐跳追加的，最左侧那一段完全由客户端控制，攻击者可以每次请求伪造
     * 一个不同的 IP 从而绕过基于 IP 的限流。信任链只能从右往左数，
     * 最右侧那一段是我们自己的 Nginx 追加的，才可信。
     *
     * <p>若前置代理层数大于 1，应改为从右往左跳过已知代理数后再取。
     */
    public static String realIp(HttpServletRequest req) {
        String xff = req.getHeader(XFF);
        if (hasText(xff)) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].trim();
            if (hasText(last)) {
                return last;
            }
        }
        String realIp = req.getHeader(REAL_IP);
        if (hasText(realIp)) {
            return realIp.trim();
        }
        return req.getRemoteAddr();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank() && !"unknown".equalsIgnoreCase(s);
    }
}
