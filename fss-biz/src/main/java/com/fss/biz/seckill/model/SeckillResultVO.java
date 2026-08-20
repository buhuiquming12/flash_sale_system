package com.fss.biz.seckill.model;

import com.fss.common.enums.SeckillRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class SeckillResultVO {

    private String        requestNo;
    private Integer       status;
    private String        statusDesc;
    private String        orderNo;
    private String        failReason;
    private LocalDateTime expireTime;
    private Integer       pollAfterMs;

    public static SeckillResultVO of(SeckillRequestStatus s, String requestNo,
                                     String orderNo, String failReason) {
        return SeckillResultVO.builder()
                .requestNo(requestNo)
                .status(s.code())
                .statusDesc(s.getDesc())
                .orderNo(orderNo)
                .failReason(failReason)
                .pollAfterMs(s.isTerminal() ? 0 : 500)
                .build();
    }
}
