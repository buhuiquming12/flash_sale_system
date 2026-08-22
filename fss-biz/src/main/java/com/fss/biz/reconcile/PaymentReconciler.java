package com.fss.biz.reconcile;

import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.domain.dto.PaymentDiff;
import com.fss.domain.mapper.PaymentMapper;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付对账。四类差异，只有一类可以自动修。
 *
 * <table>
 *   <tr><th>类</th><th>差异</th><th>处置</th><th>级别</th></tr>
 *   <tr><td>A</td><td>支付成功但订单仍<b>待支付</b></td><td>自动补推订单状态 + locked→sold</td><td>P2</td></tr>
 *   <tr><td>B</td><td>订单已支付但无成功流水</td><td>人工</td><td>P1</td></tr>
 *   <tr><td>C</td><td>金额不一致</td><td>人工</td><td>P1</td></tr>
 *   <tr><td>D</td><td>已取消订单出现支付成功</td><td>人工（退款流程应已处理）</td><td>P1</td></tr>
 * </table>
 *
 * <h3>A 类的查询条件必须是 {@code o.status = 0}（与设计文档的偏差）</h3>
 * docs/05 §9.3 写的是 {@code o.status NOT IN (1,3,4,5)}，那把 {@code status = 2}
 * （已取消）也捞进来了——而"支付成功 + 订单已取消"是 D 类，处置方式<b>正好相反</b>：
 * A 类补推订单到已支付，D 类绝不能补推（关单已经把库存还给别人了，补推等于超卖）。
 * 两类混在一条查询里，自动修复逻辑会把资损事件"修"成超卖事件——
 * 这是这一节里最危险的一行 SQL。
 *
 * <h3>A 类补推为什么必须连库存一起动</h3>
 * 只把 {@code t_order.status} 改成已支付是不够的：正常支付路径还会做
 * {@code locked → sold}。漏掉它的话库存等式 {@code total = available + locked + sold}
 * 仍然成立（两个字段都没动），但 {@code locked} 里永久留着一份已经卖掉的量，
 * 于是这份库存既不能再卖也不会被回补——一个安静的少卖，而且库存对账发现不了它
 * （等式 1 和 2 都还成立）。
 *
 * <h3>B、C、D 一律人工，不是偷懒</h3>
 * B 类有两种成因（流水被误改 / 订单被误推进），修复方向相反；
 * C 类意味着验金额那一层被绕过了，先要查清怎么绕过的；
 * D 类涉及真实资金，退款必须有人确认到账。这三类自动猜错一次的代价都是资金账目错乱，
 * 而"发现了并叫人来"已经是对账能提供的最大价值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciler {

    private final PaymentMapper      paymentMapper;
    private final PaymentFixer       fixer;
    private final ReconcileRecorder  recorder;
    private final SeckillMetrics     metrics;
    private final AlarmService       alarm;

    /** @return 本轮发现的差异条数 */
    public int reconcile(LocalDateTime before, int limit) {
        int diffs = 0;
        diffs += handleA(paymentMapper.selectPaidButOrderPending(before, limit));
        diffs += handleManual(paymentMapper.selectPaidWithoutSuccessPayment(before, limit),
                "PAID_WITHOUT_PAYMENT",
                "订单已支付但没有成功流水：流水被误改或订单被误推进，两种成因修复方向相反");
        diffs += handleManual(paymentMapper.selectAmountMismatch(limit),
                "AMOUNT_MISMATCH",
                "流水金额与订单应付金额不一致：验金额那一层被绕过了，先查清路径");
        diffs += handleManual(paymentMapper.selectPaidButOrderCancelled(limit),
                "PAID_ORDER_CANCELLED",
                "已取消订单存在成功支付：应由退款流程处理，走到这里说明退款没落上");
        return diffs;
    }

    private int handleA(List<PaymentDiff> list) {
        int n = 0;
        for (PaymentDiff d : list) {
            n++;
            try {
                // 通过独立 bean 调用，事务才真的生效——同类自调用会绕过 Spring 代理，
                // 见 PaymentFixer 的类注释
                int fixed = fixer.fixPaidOrderPending(d);
                recorder.record(ReconcileTaskType.PAYMENT, d.getPayNo(),
                        d.getActivityId(), d.getSkuId(), detail("PAID_ORDER_PENDING", d),
                        fixed > 0 ? ReconcileTaskStatus.AUTO_FIXED
                                : ReconcileTaskStatus.NEED_MANUAL,
                        fixed > 0 ? "已补推订单至已支付并完成 locked→sold"
                                : "补推时订单状态已变化，下一轮重新判定");
                alarm.p2(AlarmService.Event.PAYMENT_DIFF, d.getOrderNo(),
                        "支付成功但订单待支付，已自动补推=" + (fixed > 0));
                log.warn("stage=RECONCILE_PAY type=PAID_ORDER_PENDING orderNo={} payNo={} fixed={}",
                        d.getOrderNo(), d.getPayNo(), fixed > 0);
            } catch (Exception e) {
                metrics.jobError("reconcile-payment");
                recorder.record(ReconcileTaskType.PAYMENT, d.getPayNo(),
                        d.getActivityId(), d.getSkuId(), detail("PAID_ORDER_PENDING", d),
                        ReconcileTaskStatus.NEED_MANUAL,
                        "自动补推失败: " + e.getMessage());
                alarm.p1(AlarmService.Event.PAYMENT_DIFF, d.getOrderNo(),
                        "支付成功但订单待支付，自动补推失败: " + e.getMessage());
                log.error("stage=RECONCILE_PAY orderNo={} result=FIX_FAILED", d.getOrderNo(), e);
            }
        }
        return n;
    }

    /**
     * B / C / D 三类：一律落任务 + P1 告警，不自动修。
     *
     * <p>全部判 P1 是因为这三类都直接涉及资金：B 是钱与订单对不上，
     * C 意味着金额校验被绕过，D 是钱收了而货已经给别人了。
     * 分级的意义在于"要不要现在叫人"，这三类的答案都是要。
     */
    private int handleManual(List<PaymentDiff> list, String type, String why) {
        for (PaymentDiff d : list) {
            String bizNo = d.getPayNo() != null ? d.getPayNo() : d.getOrderNo();
            recorder.record(ReconcileTaskType.PAYMENT, bizNo,
                    d.getActivityId(), d.getSkuId(), detail(type, d),
                    ReconcileTaskStatus.NEED_MANUAL, why);
            alarm.p1(AlarmService.Event.PAYMENT_DIFF, bizNo, type + ": " + why);
            log.error("stage=RECONCILE_PAY type={} orderNo={} payNo={} amount={} orderAmount={}",
                    type, d.getOrderNo(), d.getPayNo(), d.getAmount(), d.getOrderAmount());
        }
        return list.size();
    }

    private static Map<String, Object> detail(String type, PaymentDiff d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("orderNo", d.getOrderNo());
        m.put("payNo", d.getPayNo());
        m.put("payAmount", d.getAmount());
        m.put("orderAmount", d.getOrderAmount());
        m.put("orderStatus", d.getOrderStatus());
        m.put("payStatus", d.getPayStatus());
        m.put("finishTime", String.valueOf(d.getFinishTime()));
        return m;
    }
}
