package com.fss.common.result;

import com.fss.common.error.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>约定：业务失败一律返回 HTTP 200 + 非 0 {@code code}。只有未登录(401)、
 * 无权限(403)、限流(429)、已受理(202)、降级(503) 用非 200 状态码，
 * 目的是让代理与客户端 SDK 能按标准语义处理。
 */
@Data
public class R<T> implements Serializable {

    private int    code;
    private String message;
    private T      data;
    /** 始终返回，便于用户报障时定位链路 */
    private String traceId;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = ErrorCode.SUCCESS.getCode();
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(ErrorCode ec) {
        return fail(ec, ec.getMessage());
    }

    public static <T> R<T> fail(ErrorCode ec, String message) {
        R<T> r = new R<>();
        r.code = ec.getCode();
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }
}
