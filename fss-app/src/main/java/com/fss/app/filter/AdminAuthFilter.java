package com.fss.app.filter;

import com.fss.common.context.UserContext;
import com.fss.common.error.ErrorCode;
import com.fss.common.result.R;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 管理接口鉴权。
 *
 * <p>管理接口用独立路径前缀 {@code /api/admin/}，便于在网关层直接隔离——
 * 只放行内网来源，比在应用里逐个方法加注解更可靠。这里的过滤器是第二道。
 *
 * <p>注意：过滤器里<b>不能靠抛 {@code BizException} 让全局异常处理器接管</b>。
 * 过滤器在 DispatcherServlet 之前执行，抛出的异常直接交给容器，用户看到的是
 * 500 错误页而不是统一响应体。所以这里直接写响应。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminAuthFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        UserContext.Principal p = UserContext.current();
        if (p == null) {
            write(resp, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, null);
            return;
        }
        if (!p.isAdmin()) {
            write(resp, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "需要管理员权限");
            return;
        }
        chain.doFilter(req, resp);
    }

    private void write(HttpServletResponse resp, HttpStatus status,
                       ErrorCode ec, String msg) throws IOException {
        resp.setStatus(status.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        R<Void> body = msg == null ? R.fail(ec) : R.fail(ec, msg);
        body.setTraceId(TraceContext.get());
        resp.getWriter().write(JsonUtil.toJson(body));
    }
}
