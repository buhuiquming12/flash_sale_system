package com.fss.biz.payment.service.impl;

import com.fss.biz.payment.core.PaySignUtil;
import com.fss.biz.payment.model.PayCreateCmd;
import com.fss.biz.payment.model.PayCreateVO;
import com.fss.biz.payment.model.PayNotifyCmd;
import com.fss.biz.payment.model.PayStatusVO;
import com.fss.biz.payment.service.PaymentService;
import com.fss.biz.payment.service.RefundService;
import com.fss.common.enums.OrderStatus;
import com.fss.common.enums.PayStatus;
import com.fss.common.error.Assert;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.util.IdGenerator;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.Order;
import com.fss.domain.entity.Payment;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.PaymentMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.infra.config.FssProperties;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentMapper      paymentMapper;
    private final OrderMapper        orderMapper;
    private final SeckillGoodsMapper goodsMapper;
    private final RefundService      refundService;
    private final SeckillMetrics     metrics;
    private final FssProperties      props;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayCreateVO create(PayCreateCmd cmd, long userId) {
        Order order = orderMapper.selectByOrderNoAndUser(cmd.getOrderNo(), userId);
        Assert.requireFound(order, ErrorCode.ORDER_NOT_FOUND);

        if (order.getStatus() == OrderStatus.PAID.code()) {
            throw new BizException(ErrorCode.PAY_ALREADY_DONE);
        }
        Assert.require(order.getStatus() == OrderStatus.PENDING_PAY.code(),
                ErrorCode.ORDER_STATUS_ILLEGAL, "订单当前状态不可支付");
        if (order.getExpireTime().isBefore(LocalDateTime.now())) {
            // 已过期但还没被关单任务处理到。这里不代为关单，只拒绝支付——
            // 否则支付接口就有了改订单状态的副作用，责任边界会变模糊
            throw new BizException(ErrorCode.ORDER_EXPIRED);
        }

        // 复用已有待支付流水，避免同一订单产生多条流水
        Payment exist = paymentMapper.selectPendingByOrderNo(cmd.getOrderNo());
        if (exist != null) {
            return new PayCreateVO(exist.getPayNo(), exist.getAmount(), payUrl(exist.getPayNo()));
        }

        Payment pay = Payment.builder()
                .payNo(IdGenerator.payNo())
                .orderNo(order.getOrderNo())
                .userId(userId)
                .amount(order.getPayAmount())
                .channel(0)
                .status(PayStatus.PENDING.code())
                .build();
        paymentMapper.insert(pay);
        log.info("stage=PAY_CREATE orderNo={} payNo={} amount={}",
                order.getOrderNo(), pay.getPayNo(), pay.getAmount());
        return new PayCreateVO(pay.getPayNo(), pay.getAmount(), payUrl(pay.getPayNo()));
    }

    /**
     * 渠道回调。
     *
     * <p><b>校验顺序不能变</b>：先验签 → 再验时间戳 → 再查流水 → 再比金额 → 最后改状态。
     * 验签放最前面是因为签名不过就不该泄漏"这个 payNo 存不存在"；
     * 时间戳放第二是为了在做任何数据库查询之前挡掉重放。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notify(PayNotifyCmd cmd, Map<String, String> rawParams) {
        // 1. 验签
        if (!PaySignUtil.verify(rawParams, props.getPay().getNotifySecret(), cmd.getSign())) {
            log.warn("stage=PAY_NOTIFY result=SIGN_INVALID payNo={}", cmd.getPayNo());
            throw new BizException(ErrorCode.PAY_SIGN_INVALID);
        }

        // 2. 时间戳防重放
        long skew = Math.abs(System.currentTimeMillis() - cmd.getTimestamp());
        if (skew > props.getPay().getNotifyTolerance().toMillis()) {
            log.warn("stage=PAY_NOTIFY result=TIMESTAMP_EXPIRED payNo={} skewMs={}",
                    cmd.getPayNo(), skew);
            throw new BizException(ErrorCode.PAY_SIGN_INVALID, "回调已过期");
        }

        // 3. 查流水
        Payment pay = paymentMapper.selectByPayNo(cmd.getPayNo());
        Assert.requireFound(pay, ErrorCode.ORDER_NOT_FOUND);
        if (pay.getStatus() == PayStatus.SUCCESS.code()) {
            log.info("stage=PAY_NOTIFY payNo={} result=IDEMPOTENT", cmd.getPayNo());
            return;                       // 重复回调，幂等返回
        }

        // 4. 比金额。渠道回调里的金额一律与本地流水核对，绝不采信
        if (pay.getAmount().compareTo(cmd.getAmount()) != 0) {
            log.error("stage=PAY_NOTIFY result=AMOUNT_MISMATCH payNo={} expect={} actual={}",
                    cmd.getPayNo(), pay.getAmount(), cmd.getAmount());
            throw new BizException(ErrorCode.PAY_AMOUNT_MISMATCH);
        }

        if (!"SUCCESS".equalsIgnoreCase(cmd.getStatus())) {
            paymentMapper.updateStatus(cmd.getPayNo(),
                    PayStatus.PENDING.code(), PayStatus.FAILED.code());
            log.info("stage=PAY_NOTIFY payNo={} result=CHANNEL_FAILED", cmd.getPayNo());
            return;
        }

        // 5. 改状态
        paySuccess(pay, cmd, JsonUtil.toJson(rawParams));
    }

    /**
     * 支付成功落账，含与关单的竞态处理。
     *
     * <p>支付和关单都用 {@code WHERE status = PENDING_PAY} 竞争同一行，行锁保证只有
     * 一方成功。<b>输的一方必须读最新状态重新决策，不能直接报错了事</b>——
     * 如果输给了关单，钱已经收了，必须转退款并告警，否则就是真实资损。
     */
    private void paySuccess(Payment pay, PayNotifyCmd cmd, String rawBody) {
        String orderNo = pay.getOrderNo();

        int rows = orderMapper.markPaid(orderNo,
                OrderStatus.PENDING_PAY.code(), OrderStatus.PAID.code());
        if (rows == 0) {
            Order latest = orderMapper.selectByOrderNo(orderNo);
            int status = latest == null ? -1 : latest.getStatus();

            if (status == OrderStatus.PAID.code()) {
                // 并发双回调，另一方已处理完
                markPaymentSuccess(cmd, rawBody);
                return;
            }
            if (status == OrderStatus.CANCELLED.code()) {
                // 钱收到了但订单已关闭 → 先如实记成成功流水（钱确实收了，
                // 账不能不认），再走退款。顺序反了的话 refund 的
                // "from = SUCCESS" 条件更新会一行都改不到，退款静默失败
                markPaymentSuccess(cmd, rawBody);
                refundService.refund(cmd.getPayNo(), orderNo,
                        "支付与关单竞态：订单已关闭，库存已回补给其他用户");
                log.error("stage=PAY_CANCEL_RACE orderNo={} payNo={} action=REFUND",
                        orderNo, cmd.getPayNo());
                return;
            }
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单状态不允许支付: " + status);
        }

        markPaymentSuccess(cmd, rawBody);

        Order order = orderMapper.selectByOrderNo(orderNo);
        // locked → sold：此时库存真正卖出，不再可回补
        int moved = goodsMapper.moveLockedToSold(
                order.getActivityId(), order.getSkuId(), order.getQuantity());
        if (moved == 0) {
            log.error("stage=PAY_SUCCESS orderNo={} warn=LOCKED_STOCK_INSUFFICIENT "
                    + "库存账目异常，需对账介入", orderNo);
        }
        metrics.orderPaid(order.getActivityId(), order.getSkuId());
        log.info("stage=PAY_SUCCESS orderNo={} payNo={} amount={}",
                orderNo, cmd.getPayNo(), cmd.getAmount());
    }

    private void markPaymentSuccess(PayNotifyCmd cmd, String rawBody) {
        try {
            paymentMapper.markSuccess(cmd.getPayNo(), cmd.getOutTradeNo(), rawBody);
        } catch (DuplicateKeyException e) {
            // uk_out_trade_no：同一渠道流水号重复回调，幂等
            log.info("stage=PAY_NOTIFY payNo={} outTradeNo={} result=DUP_OUT_TRADE_NO",
                    cmd.getPayNo(), cmd.getOutTradeNo());
        }
    }

    @Override
    public PayStatusVO status(String orderNo, long userId) {
        Order order = orderMapper.selectByOrderNoAndUser(orderNo, userId);
        Assert.requireFound(order, ErrorCode.ORDER_NOT_FOUND);

        Payment success = paymentMapper.selectSuccessByOrderNo(orderNo);
        Payment pay = success != null ? success : paymentMapper.selectPendingByOrderNo(orderNo);
        return new PayStatusVO(orderNo,
                order.getStatus(), OrderStatus.of(order.getStatus()).getDesc(),
                pay == null ? null : pay.getStatus(),
                pay == null ? null : PayStatus.of(pay.getStatus()).getDesc(),
                order.getPayTime());
    }

    @Override
    public Map<String, String> mockSign(String payNo, String outTradeNo,
                                        String amount, String status) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("payNo", payNo);
        p.put("outTradeNo", outTradeNo == null ? IdGenerator.outTradeNo() : outTradeNo);
        p.put("amount", amount);
        p.put("status", status);
        p.put("timestamp", String.valueOf(System.currentTimeMillis()));
        p.put("sign", PaySignUtil.sign(p, props.getPay().getNotifySecret()));
        return p;
    }

    private String payUrl(String payNo) {
        return "/mock-pay.html?payNo=" + payNo;
    }

    /** 金额比较统一用 compareTo，绝不用 equals —— BigDecimal 的 equals 会比较标度 */
    @SuppressWarnings("unused")
    private static boolean amountEquals(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }
}
