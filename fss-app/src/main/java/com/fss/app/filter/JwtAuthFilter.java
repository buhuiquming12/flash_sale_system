package com.fss.app.filter;

import com.fss.biz.user.core.JwtService;
import com.fss.common.context.UserContext;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.result.R;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    /** 无需认证的路径前缀 */
    private static final List<String> WHITELIST = List.of(
            "/api/user/register",
            "/api/user/login",
            "/api/activity/list",
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
        String header = req.getHeader("Authorization");

        // 活动详情允许匿名浏览，但带了 token 就解析出身份（用于展示"已抢过"等状态）
        boolean optional = req.getRequestURI().startsWith("/api/activity/");
        if (header == null || !header.startsWith(BEARER)) {
            if (optional) {
                chain.doFilter(req, resp);
                return;
            }
            reject(resp, ErrorCode.UNAUTHORIZED);
            return;
        }

        try {
            JwtService.Payload p = jwtService.verify(header.substring(BEARER.length()).trim());
            UserContext.set(p.userId(), p.username(), p.role());
        } catch (BizException e) {
            if (!optional) {
                reject(resp, e.getErrorCode());
                return;
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            UserContext.clear();
        }
    }

    private void reject(HttpServletResponse resp, ErrorCode ec) throws IOException {
        resp.setStatus(ec == ErrorCode.FORBIDDEN
                ? HttpStatus.FORBIDDEN.value() : HttpStatus.UNAUTHORIZED.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        R<Void> body = R.fail(ec);
        body.setTraceId(TraceContext.get());
        resp.getWriter().write(JsonUtil.toJson(body));
    }
}
