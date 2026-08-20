package com.fss.common.enums;

/**
 * 带显式整数编码的枚举。
 *
 * <p>所有落库的状态值一律用显式整数，<b>禁止依赖 {@code ordinal()}</b>——
 * 枚举顺序调整会静默改变库中已有数据的语义。
 */
public interface CodeEnum {

    int code();

    static <E extends Enum<E> & CodeEnum> E of(Class<E> type, int code) {
        for (E e : type.getEnumConstants()) {
            if (e.code() == code) {
                return e;
            }
        }
        throw new IllegalArgumentException(
                "未知的 " + type.getSimpleName() + " 编码: " + code);
    }
}
