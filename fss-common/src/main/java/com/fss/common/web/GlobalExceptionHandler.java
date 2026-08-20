package com.fss.common.web;

import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>HTTP 状态码策略：业务失败一律 200 + 非 0 code，只有下列情况用非 200——
 * 401 未登录、403 无权限、429 限流、503 降级。
 * 限流用 429 是为了让 Nginx、网关、客户端 SDK 能按标准语义自动退避。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    /** 业务码 → HTTP 状态码的例外映射，未列出的走 200 */
    private static final Map<ErrorCode, HttpStatus> HTTP_OVERRIDE = Map.of(
            ErrorCode.UNAUTHORIZED,     HttpStatus.UNAUTHORIZED,
            ErrorCode.FORBIDDEN,        HttpStatus.FORBIDDEN,
            ErrorCode.RATE_LIMITED,     HttpStatus.TOO_MANY_REQUESTS,
            ErrorCode.SERVICE_DEGRADED, HttpStatus.SERVICE_UNAVAILABLE);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e, HttpServletRequest req) {
        ErrorCode ec = e.getErrorCode();
        // 业务预期内的失败（库存不足、重复购买）峰值时每秒上万次，不打 error 级日志
        if (ec == ErrorCode.SYSTEM_ERROR || ec == ErrorCode.SYSTEM_BUSY) {
            log.error("业务异常 code={} uri={} msg={}", ec.getCode(), req.getRequestURI(), e.getMessage(), e);
        } else if (log.isDebugEnabled()) {
            log.debug("业务失败 code={} uri={} msg={}", ec.getCode(), req.getRequestURI(), e.getMessage());
        }
        HttpStatus status = HTTP_OVERRIDE.getOrDefault(ec, HttpStatus.OK);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header("Retry-After", "1");
        }
        return builder.body(R.fail(ec, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        return R.fail(ErrorCode.PARAM_INVALID, fieldErrors(e.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(BindException.class)
    public R<Void> handleBind(BindException e) {
        return R.fail(ErrorCode.PARAM_INVALID, fieldErrors(e.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public R<Void> handleBadRequest(Exception e) {
        return R.fail(ErrorCode.PARAM_INVALID, e.getMessage());
    }

    @ExceptionHandler({NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    public R<Void> handleNotFound(Exception e) {
        return R.fail(ErrorCode.PARAM_INVALID, e.getMessage());
    }

    /**
     * 兜底。绝不把原始异常信息返回给客户端——
     * SQL 片段、类名、栈帧都是攻击者的信息来源。客户端只拿 traceId 报障。
     */
    @ExceptionHandler(Throwable.class)
    public R<Void> handleOther(Throwable t, HttpServletRequest req) {
        log.error("未处理异常 uri={} method={}", req.getRequestURI(), req.getMethod(), t);
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }

    private String fieldErrors(java.util.List<FieldError> errors) {
        return errors.stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
    }
}
