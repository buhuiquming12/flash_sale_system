package com.fss.common.error;

import lombok.Getter;

/**
 * 业务异常。携带 {@link ErrorCode}，由全局异常处理器转换为统一响应体。
 *
 * <p>默认不填充堆栈（{@code super(msg, null, false, false)}）：秒杀链路上
 * "库存不足""重复购买"是<b>正常业务结论</b>而非故障，峰值时每秒会抛上万次，
 * 填充堆栈的开销和日志噪音都不可接受。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    /** 需要保留原始堆栈时使用（系统类异常包装） */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public boolean isDeterministic() {
        return errorCode.isDeterministic();
    }
}
