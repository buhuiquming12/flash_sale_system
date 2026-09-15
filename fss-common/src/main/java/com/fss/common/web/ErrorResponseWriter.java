package com.fss.common.web;

import com.fss.common.error.ErrorCode;
import com.fss.common.result.R;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * 过滤器 / 拦截器里直接写 JSON 错误响应的唯一出口。
 *
 * <p><b>为什么必须有这个类。</b> {@link TraceIdResponseAdvice} 只能给 controller 的返回体
 * 补 traceId，而过滤器和拦截器跑在 DispatcherServlet 之前，写出去的字节根本不经过它。
 * 于是每个直接写响应的地方都得自己记得 {@code setTraceId} —— 三处各写一遍的结果是
 * 已经漏了一处：{@code RateLimitInterceptor} 那处没设，429 的响应头里有
 * {@code X-Trace-Id}、响应体里却是 {@code null}，而这恰好是用户最需要报障定位的那类响应。
 *
 * <p><b>为什么这里不抛 {@code BizException} 交给 {@link GlobalExceptionHandler}。</b>
 * 过滤器在 DispatcherServlet 之前执行，抛出的异常直接交给容器，用户看到的是
 * 500 错误页而不是统一响应体；{@code preHandle} 抛异常还不会触发
 * {@code afterCompletion}，容易漏清理。
 */
public final class ErrorResponseWriter {

    private ErrorResponseWriter() {
    }

    /** 用 {@link ErrorCode} 自带的文案 */
    public static void write(HttpServletResponse resp, HttpStatus status, ErrorCode ec)
            throws IOException {
        write(resp, status, ec, null);
    }

    /**
     * @param msg 为 {@code null} 时用 {@link ErrorCode} 自带文案
     */
    public static void write(HttpServletResponse resp, HttpStatus status, ErrorCode ec, String msg)
            throws IOException {
        resp.setStatus(status.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        R<Void> body = msg == null ? R.fail(ec) : R.fail(ec, msg);
        body.setTraceId(TraceContext.get());
        resp.getWriter().write(JsonUtil.toJson(body));
    }
}
