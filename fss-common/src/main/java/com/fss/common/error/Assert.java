package com.fss.common.error;

/** 业务前置条件断言。失败即抛 {@link BizException}，不静默吞掉。 */
public final class Assert {

    private Assert() {
    }

    public static void require(boolean condition, ErrorCode ec) {
        if (!condition) {
            throw new BizException(ec);
        }
    }

    public static void require(boolean condition, ErrorCode ec, String message) {
        if (!condition) {
            throw new BizException(ec, message);
        }
    }

    /** 参数/配置校验的快捷方式，失败为 PARAM_INVALID */
    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
    }

    public static <T> T requireFound(T value, ErrorCode ec) {
        if (value == null) {
            throw new BizException(ec);
        }
        return value;
    }
}
