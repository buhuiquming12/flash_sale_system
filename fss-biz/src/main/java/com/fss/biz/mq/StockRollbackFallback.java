package com.fss.biz.mq;

import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.ErrorCode;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.domain.message.StockRollbackMessage;
import com.fss.infra.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 同步补偿失败后，把同一回补意图交给可靠消息链路。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRollbackFallback {

    private final ReliableMqProducer producer;

    public void publish(OrderCreateMessage msg, ErrorCode ec, String reason) {
        try {
            producer.sendReliable(MqTopics.STOCK_ROLLBACK, msg.getRequestNo(),
                    StockRollbackMessage.builder()
                            .requestNo(msg.getRequestNo())
                            .userId(msg.getUserId())
                            .activityId(msg.getActivityId())
                            .skuId(msg.getSkuId())
                            .quantity(msg.getQuantity())
                            .reason(reason)
                            .keepBought(ec == ErrorCode.ALREADY_BOUGHT)
                            .failStatus(statusOf(ec).code())
                            .traceId(msg.getTraceId())
                            .version(StockRollbackMessage.CURRENT_VERSION)
                            .build(), null);
            log.warn("stage=STOCK_ROLLBACK_FALLBACK requestNo={} result=REGISTERED", msg.getRequestNo());
        } catch (Exception e) {
            // 原同步回补已经失败；登记再失败只能交给对账，不能覆盖原业务异常。
            log.error("stage=STOCK_ROLLBACK_FALLBACK requestNo={} result=REGISTER_FAILED",
                    msg.getRequestNo(), e);
        }
    }

    private static SeckillRequestStatus statusOf(ErrorCode ec) {
        return switch (ec) {
            case STOCK_NOT_ENOUGH -> SeckillRequestStatus.STOCK_NOT_ENOUGH;
            case ALREADY_BOUGHT -> SeckillRequestStatus.ALREADY_BOUGHT;
            default -> SeckillRequestStatus.COMPENSATED;
        };
    }
}
