package com.fss.biz.seckill.model;

import com.fss.common.enums.SeckillRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀提交结果。
 *
 * <p>阶段一同步落库，直接带回 orderNo，status = SUCCESS。
 * 阶段三异步化后同一个 VO 会返回 status = QUEUEING 且 orderNo 为空，
 * 客户端按 {@code pollAfterMs} 轮询结果接口。接口形状保持一致，
 * 这样从同步切到异步时前端不用改。
 */
@Data
@Builder
@AllArgsConstructor
public class SeckillSubmitVO {

    private String        requestNo;
    private Integer       status;
    private String        statusDesc;
    private String        orderNo;
    private Integer       remainStock;
    private LocalDateTime expireTime;
    /** 服务端下发的下次轮询间隔，是一个软限流手段 */
    private Integer       pollAfterMs;

    public static SeckillSubmitVO success(String requestNo, String orderNo,
                                          int remainStock, LocalDateTime expireTime) {
        return SeckillSubmitVO.builder()
                .requestNo(requestNo)
                .status(SeckillRequestStatus.SUCCESS.code())
                .statusDesc(SeckillRequestStatus.SUCCESS.getDesc())
                .orderNo(orderNo)
                .remainStock(remainStock)
                .expireTime(expireTime)
                .pollAfterMs(0)
                .build();
    }

    public static SeckillSubmitVO queueing(String requestNo, int remainStock, int pollAfterMs) {
        return SeckillSubmitVO.builder()
                .requestNo(requestNo)
                .status(SeckillRequestStatus.QUEUEING.code())
                .statusDesc(SeckillRequestStatus.QUEUEING.getDesc())
                .remainStock(remainStock)
                .pollAfterMs(pollAfterMs)
                .build();
    }
}
