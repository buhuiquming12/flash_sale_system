package com.fss.biz.seckill.core;

import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.ErrorCode;
import com.fss.domain.entity.SeckillRequest;
import com.fss.domain.mapper.SeckillRequestMapper;
import com.fss.domain.message.OrderCreateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 补偿回补：Redis 预扣成功但订单最终没能创建时，把库存（有时连资格）还回去。
 *
 * <p>三个地方会调它，全都是"这条请求已经没有别的出路了"：
 * <ol>
 *   <li>消费端遇到<b>确定性失败</b>——重试 5 次也不会成功，立刻回补比白占 5 分钟库存好</li>
 *   <li>重发任务耗尽重试次数（{@code onSendGiveUp}）——消息根本没发出去</li>
 *   <li>死信队列消费者——消息发出去了但消费端反复失败</li>
 * </ol>
 * 三条路径共用一个实现，因为"要还什么"的判断逻辑完全相同，而这个判断
 * （{@code keepBought} 与 {@code failStatus}）是全系统最容易写错的地方之一。
 *
 * <p><b>为什么不在这里包事务</b>：主要动作是 Redis 的一段 Lua，它自己就是原子的；
 * 后面写 {@code t_seckill_request} 是纯记录，失败了不影响库存正确性。
 * 包上事务反而会让"Redis 已回补而 DB 记录回滚"这种组合出现，
 * 而 Redis 的操作是没法跟着 DB 事务回滚的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillCompensateService {

    private final SeckillExecutor      executor;
    private final SeckillRequestMapper requestMapper;

    /**
     * 回补一条请求。
     *
     * @param ec 落库失败的错误码，决定 {@code keepBought} 与请求终态
     * @return 见 {@link RollbackOutcome}。调用方<b>必须</b>区分
     *         {@code ALREADY_DONE}（重复投递，正常）与 {@code FAILED}（库存可能泄漏，要告警）
     */
    public RollbackOutcome rollback(OrderCreateMessage msg, ErrorCode ec, String reason) {
        boolean keepBought = keepBought(ec);
        SeckillRequestStatus status = failStatusOf(ec);

        RollbackOutcome outcome = executor.rollback(msg.getActivityId(), msg.getSkuId(),
                msg.getUserId(), msg.getQuantity(), msg.getRequestNo(), ec.getMessage(),
                keepBought, status);
        recordFailure(msg, status, reason);
        return outcome;
    }

    /**
     * 落库失败时是否<b>保留</b>用户购买资格。
     *
     * <p>{@code ALREADY_BOUGHT} 是唯一必须保留的情形：它说明 DB 里已经有这个用户
     * 在本活动本 SKU 上的订单（哪怕已取消，唯一键仍然占着）。归还资格 → 用户重抢 →
     * Redis 放行 → DB 又冲突 → 又回补，无限循环。
     *
     * <p>其余失败（系统异常、商品不存在、DB 库存真的不足）用户没有责任，资格要还给他。
     */
    private boolean keepBought(ErrorCode ec) {
        return ec == ErrorCode.ALREADY_BOUGHT;
    }

    /**
     * 请求的终态。
     *
     * <p>确定性失败写<b>具体</b>的码（2 库存不足 / 3 已参与过），只有系统原因才写
     * 5（COMPENSATED，"系统繁忙已退回"）。区别不是措辞——用户看到"系统繁忙"会不停重试，
     * 看到"您已参与过本次秒杀"就不会。
     */
    private SeckillRequestStatus failStatusOf(ErrorCode ec) {
        return switch (ec) {
            case STOCK_NOT_ENOUGH -> SeckillRequestStatus.STOCK_NOT_ENOUGH;
            case ALREADY_BOUGHT   -> SeckillRequestStatus.ALREADY_BOUGHT;
            // 系统异常、投递失败、进死信：库存与资格都已归还，请求置 COMPENSATED
            default               -> SeckillRequestStatus.COMPENSATED;
        };
    }

    /**
     * 失败结论落 {@code t_seckill_request}，供用户查询与对账。
     *
     * <p>必须能容忍已存在：这条请求可能已经被 {@code OrderCreateService.upsertRequest}
     * 写过一行（消费端在 insert 订单之前就写了请求记录的情形），
     * 也可能被上一次回补写过。用 advance-then-insert 而不是 insert-then-catch：
     * 后者每次重复都会产生一次 {@code DuplicateKeyException} 与事务回滚的日志噪音。
     *
     * <p>写不进去不影响给用户的结论（Redis 里已经有了），所以异常只记日志——
     * 不能因为审计记录写失败就把业务错误码换成系统错误。
     */
    private void recordFailure(OrderCreateMessage msg, SeckillRequestStatus status, String reason) {
        try {
            int rows = requestMapper.advanceStatus(msg.getRequestNo(), status.code(), null, reason);
            if (rows == 0 && requestMapper.selectByRequestNo(msg.getRequestNo()) == null) {
                requestMapper.insert(SeckillRequest.builder()
                        .requestNo(msg.getRequestNo())
                        .userId(msg.getUserId())
                        .activityId(msg.getActivityId())
                        .skuId(msg.getSkuId())
                        .quantity(msg.getQuantity())
                        .status(status.code())
                        .failReason(reason)
                        .traceId(msg.getTraceId())
                        .build());
            }
        } catch (Exception e) {
            log.warn("秒杀失败记录写入失败 requestNo={} status={}",
                    msg.getRequestNo(), status, e);
        }
    }
}
