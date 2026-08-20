package com.fss.common.trace;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 链路号上下文。
 *
 * <p>异步路径（缓存重建、事务提交后回调、定时任务、@Async）必须用
 * {@link #wrap(Runnable)} 包装，否则日志会丢 traceId。而这些异步路径恰好是
 * 最需要追踪的地方——同步链路出问题看响应就行，异步链路只能靠日志。
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String HEADER   = "X-Trace-Id";

    private TraceContext() {
    }

    public static String get() {
        return MDC.get(TRACE_ID);
    }

    public static void set(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
    }

    public static Runnable wrap(Runnable task) {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            if (ctx != null) {
                MDC.setContextMap(ctx);
            }
            try {
                task.run();
            } finally {
                restore(prev);
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            if (ctx != null) {
                MDC.setContextMap(ctx);
            }
            try {
                return task.call();
            } finally {
                restore(prev);
            }
        };
    }

    private static void restore(Map<String, String> prev) {
        if (prev == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(prev);
        }
    }
}
