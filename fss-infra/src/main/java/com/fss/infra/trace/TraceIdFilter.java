package com.fss.infra.trace;

import com.fss.common.trace.TraceContext;
import com.fss.common.util.IdGenerator;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 链路号过滤器。必须是最高优先级——排在它后面的过滤器打的日志就没有 traceId 了。
 *
 * <p>透传客户端带来的 {@code X-Trace-Id}，同时回写到响应头，这样用户报障时
 * 直接把响应头里的 traceId 给过来就能定位。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        String traceId = r.getHeader(TraceContext.HEADER);
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            traceId = IdGenerator.traceId();
        }
        TraceContext.set(traceId);
        ((HttpServletResponse) resp).setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            TraceContext.clear();
        }
    }
}
