package com.fss.app.filter;

import com.fss.common.error.ErrorCode;
import com.fss.common.web.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 请求路径守卫：把"无法可靠判定"的路径在最前面拒掉。
 *
 * <h3>为什么需要它</h3>
 * 鉴权与限流都靠<b>路径字符串</b>做判定（{@code AdminAuthFilter} 看是否以
 * {@code /api/admin/} 开头，{@code JwtAuthFilter} 看是否以 {@code /api/activity/}
 * 开头），而真正决定执行哪个接口的是 Spring MVC 的 handler 匹配。两者都读
 * {@link HttpServletRequest#getRequestURI()}，但容器与 Spring 对这条路径的
 * <b>理解并不总是一致</b>：Tomcat 的 {@code getRequestURI()} 返回客户端发来的
 * <b>原始串</b>（不做 {@code .}/{@code ..} 归并），而 URL 规范允许
 * {@code /api/activity/../admin/degrade} 这类写法。
 *
 * <p>两个前缀判定叠加时就会出现缺口：{@code JwtAuthFilter} 因
 * {@code /api/activity/} 前缀直接放行，{@code AdminAuthFilter} 因"不以
 * {@code /api/admin/} 开头"整个过滤器跳过。实测（真 Tomcat 10.1.31）确认过滤器
 * 看到的确实是原始串 {@code /api/activity/../admin/degrade}。
 *
 * <p><b>当前不可利用</b>：Spring MVC 也拿原始串匹配 handler，
 * {@code /api/admin/**} 没匹配上，实测 12 个穿越变体全部 404，落不到
 * {@code AdminController}。但这是<b>巧合而不是设计</b>——只要有人在
 * {@code /api/activity/} 下加一个 {@code /**} 路由，或把
 * {@code spring.mvc.pathmatch.matching-strategy} 改成 {@code ant_path_matcher}，
 * 立刻变成真实的未鉴权管理接口入口。
 *
 * <h3>为什么不把规范化下沉到每个判定点</h3>
 * 那样每处都要和 Spring 的路径处理保持逐字一致，而 Spring 的
 * {@code UrlPathHelper} 会在版本间调整（分号参数、双斜杠、编码斜杠的处理都变过）。
 * 两套实现必然漂移，而漂移的那个瞬间就是安全缺口——这正是本类要消灭的模式。
 * 守卫只做一件不需要和任何实现对齐的事：<b>本项目的接口路径里不该出现
 * {@code .}/{@code ..} 段，也不该出现编码斜杠；出现了就拒。</b>
 *
 * <p>顺序必须排在 {@code TraceIdFilter}（{@code HIGHEST_PRECEDENCE}）之后，
 * 这样 400 响应也带 traceId；排在 {@code JwtAuthFilter}(+10) 与
 * {@code AdminAuthFilter}(+20) 之前，让它们根本看不到可疑路径。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestPathGuardFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        if (isAmbiguous(uri)) {
            log.warn("stage=PATH_GUARD result=REJECT uri={} client={}", uri, req.getRemoteAddr());
            ErrorResponseWriter.write(resp, HttpStatus.BAD_REQUEST,
                    ErrorCode.PARAM_INVALID, "请求路径不合法");
            return;
        }
        chain.doFilter(req, resp);
    }

    /** 路径是否含有无法可靠判定的成分 */
    static boolean isAmbiguous(String rawUri) {
        String lower = rawUri.toLowerCase(Locale.ROOT);
        // 编码斜杠 / 反斜杠 / NUL：本项目没有任何接口路径需要它们，
        // 而它们恰恰是绕过前缀判定的常用手法（Tomcat 对 %2f 直接 400，
        // 但 %5c 在部分配置下会被当成分隔符）
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) {
            return true;
        }
        // 先还原所有 %XX 再切段，这样 %2e%2e 与 .. 走同一条判定
        for (String segment : percentDecode(rawUri).split("/", -1)) {
            String s = stripPathParams(segment);
            if (".".equals(s) || "..".equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉 {@code ;} 之后的路径参数，与 Spring 的
     * {@code UrlPathHelper.decodeAndCleanUriString} 同语义。
     *
     * <p>少了这一步，{@code /api/activity/..;/admin/degrade} 切出来的段是
     * {@code ..;}，不等于 {@code ..}，判定就漏了。
     */
    private static String stripPathParams(String segment) {
        int i = segment.indexOf(';');
        return i < 0 ? segment : segment.substring(0, i);
    }

    /**
     * 只还原 {@code %XX}，<b>不碰 {@code +}</b>——{@code URLDecoder} 会把
     * {@code +} 解成空格，那是表单语义，套到路径上会把合法的加号路径改坏。
     */
    private static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        }
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
