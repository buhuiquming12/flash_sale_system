package com.fss.biz.payment.service;

import com.fss.common.enums.PayStatus;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.Payment;
import com.fss.domain.entity.ReconcileTask;
import com.fss.domain.mapper.PaymentMapper;
import com.fss.domain.mapper.ReconcileTaskMapper;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 退款。
 *
 * <h3>什么时候需要退款</h3>
 * 只有一种情形：<b>钱收到了，但订单已经关闭</b>。支付回调与超时关单同时到达同一订单，
 * 两者都用 {@code WHERE status = PENDING_PAY} 竞争，行锁保证只有一方赢。
 * 关单赢了而回调已经带着"渠道扣款成功"到达时，用户的钱在我们这里，
 * 订单却已经不存在——这是<b>真实资损</b>，不是一个可以记日志了事的边界情况。
 *
 * <h3>为什么不"把订单改回待支付然后正常支付"</h3>
 * 因为库存已经还给别人了。关单会同事务回补 {@code locked → available}，
 * 那一份库存可能已经被另一个用户抢走并支付。把订单拉回待支付再置成已支付，
 * 结果是两个人买到同一件——用一次资损换一次超卖，更糟。
 * <b>订单状态机里 CANCELLED 是终态，没有回头路</b>，这个设计约束在这里体现为
 * "只能退款"。
 *
 * <h3>退款为什么必须是"标记 + 转人工"而不是"标记完就算完"</h3>
 * 本项目没有真实渠道，{@code PayStatus.REFUNDED} 只表示"我们这边认为该退"。
 * 钱是否真的回到用户账上，取决于渠道的退款接口——那是一次<b>跨系统的、
 * 可能失败可能延迟的</b>调用。所以这里做的是：
 * <ol>
 *   <li>条件更新把流水置成 REFUNDED，拿到"我是唯一执行者"的许可（幂等）</li>
 *   <li>落一条支付类对账任务，状态 NEED_MANUAL，带上完整明细</li>
 *   <li>P1 告警</li>
 * </ol>
 * 接真实渠道时，第 2 步会变成"提交退款单 + 由退款结果回调推进状态"，
 * 但对账任务这一层不能去掉：渠道退款也会失败，而失败了必须有人知道。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentMapper       paymentMapper;
    private final ReconcileTaskMapper reconcileMapper;
    private final SeckillMetrics      metrics;
    private final AlarmService        alarm;

    /**
     * 为一条已成功的支付流水发起退款。
     *
     * <p><b>调用前提：该流水必须已经是 SUCCESS。</b> 直接从 PENDING 退款是没有意义的
     * ——钱还没收到，退什么？条件更新的 {@code from = SUCCESS} 就是在断言这个前提，
     * 影响行数 0 说明前提不成立（或者已经退过了），两种情况都不该继续。
     *
     * @return true 表示本次真的发起了退款；false 表示幂等命中或前提不成立
     */
    public boolean refund(String payNo, String orderNo, String reason) {
        int rows = paymentMapper.updateStatus(payNo,
                PayStatus.SUCCESS.code(), PayStatus.REFUNDED.code());
        if (rows == 0) {
            // 幂等命中（已退过）或流水不是成功态。查一次给出准确日志——
            // 这条路径太重要，不能让"没退"和"已退过"在日志里长得一样
            Payment p = paymentMapper.selectByPayNo(payNo);
            log.warn("stage=REFUND payNo={} orderNo={} result=SKIPPED status={} reason={}",
                    payNo, orderNo, p == null ? "NOT_FOUND" : PayStatus.of(p.getStatus()), reason);
            return false;
        }

        Payment pay = paymentMapper.selectByPayNo(payNo);
        recordTask(pay, orderNo, reason);

        log.error("stage=REFUND payNo={} orderNo={} amount={} reason={} "
                        + "result=MARKED_REFUNDED 需人工确认退款到账",
                payNo, orderNo, pay == null ? null : pay.getAmount(), reason);
        alarm.p1(AlarmService.Event.PAY_CANCEL_RACE, orderNo,
                "已标记退款，需人工确认到账: " + reason);
        metrics.reconcileDiff("payment", "need_manual");
        return true;
    }

    /**
     * 落对账任务。
     *
     * <p>失败只记日志、不抛：退款标记已经提交了，为了一条审计记录去回滚它反而更糟
     * （流水回到 SUCCESS，而"该退款"这件事只剩日志）。但这条日志必须是 error 级别，
     * 因为它意味着资损事件<b>没有</b>进入人工工单流。
     */
    private void recordTask(Payment pay, String orderNo, String reason) {
        try {
            reconcileMapper.insert(ReconcileTask.builder()
                    .taskType(ReconcileTaskType.PAYMENT.code())
                    .bizNo(pay == null ? orderNo : pay.getPayNo())
                    .detail(JsonUtil.toJson(Map.of(
                            "type", "PAY_CANCEL_RACE",
                            "orderNo", orderNo,
                            "payNo", pay == null ? "" : pay.getPayNo(),
                            "amount", pay == null ? "" : pay.getAmount().toPlainString(),
                            "reason", reason)))
                    .status(ReconcileTaskStatus.NEED_MANUAL.code())
                    .handleResult("已标记 REFUNDED，待人工确认渠道退款到账")
                    .build());
        } catch (Exception e) {
            log.error("stage=REFUND payNo={} result=RECORD_FAILED 资损事件未进人工工单流，只剩这条日志",
                    pay == null ? null : pay.getPayNo(), e);
        }
    }
}
