package com.fss.common.enums;

import lombok.Getter;

/**
 * 库存变更类型。
 *
 * <p>配合 {@code t_stock_log} 的 {@code uk_biz_type (biz_no, change_type)} 实现幂等：
 * 同一业务号的同一类型变更只能记一条。
 */
@Getter
public enum StockChangeType implements CodeEnum {

    /** Redis 预扣（阶段二起使用） */
    PRE_DEDUCT(1, "预扣"),
    /** DB 确认扣减，biz_no = request_no */
    CONFIRM_DEDUCT(2, "确认扣减"),
    /** 取消回补，biz_no = order_no。只还库存，不还用户资格 */
    CANCEL_RELEASE(3, "取消回补"),
    /** 补偿回补，biz_no = request_no。还库存 + 还用户资格 */
    COMPENSATE_ROLLBACK(4, "补偿回补"),
    /** 管理员调整 */
    MANUAL_ADJUST(5, "管理调整");

    private final int    code;
    private final String desc;

    StockChangeType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static StockChangeType of(int code) {
        return CodeEnum.of(StockChangeType.class, code);
    }
}
