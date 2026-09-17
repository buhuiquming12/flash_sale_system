package com.fss.biz.reconcile;

import com.fss.biz.mq.ReliableMqProducer;
import com.fss.biz.mq.StockRollbackFallback;
import com.fss.biz.seckill.core.RollbackOutcome;
import com.fss.biz.seckill.core.SeckillCompensateService;
import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.common.enums.MqStatus;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.ErrorCode;
import com.fss.common.trace.TraceContext;
import com.fss.domain.entity.MqMessage;
import com.fss.domain.entity.Order;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.config.FssProperties;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.mq.MqTopics;
import com.fss.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资格对账。
 *
 * <p>找的是<b>孤儿资格</b>：Redis 说"排队中"，但过了很久 DB 里既没有订单也没有结论。
 * 这种请求对应一份被占用的库存和一个还在轮询的用户，而前面所有机制都已经放过它了
 * ——消息表说发出去了、消费端没报错、死信队列里也没有。它是"每一层都以为别人会处理"
 * 的残留。
 *
 * <h3>不同状态对应完全不同的处置</h3>
 * <table>
 *   <tr><th>本地消息表状态</th><th>含义</th><th>动作</th></tr>
 *   <tr><td>无记录</td><td>主链路在写消息表之前就崩了</td><td>直接回补，没人会建这张订单</td></tr>
 *   <tr><td>待发送(0)</td><td>重发任务还在努力</td><td><b>什么都不做</b></td></tr>
 *   <tr><td>已发送(1)</td><td>消息发出去了但没结果</td><td>重发一次；连续 N 轮无果才回补</td></tr>
 *   <tr><td>已消费(2)</td><td>消费端处理过了</td><td>结论回写失败，补写 Redis 结论</td></tr>
 *   <tr><td>发送失败(3)</td><td>重发已放弃</td><td>{@code onSendGiveUp} 已回补过，只补结论</td></tr>
 * </table>
 * 把这五种混成一种处理（"没结论就回补"）会踩两个坑：
 * 一是抢在重发任务前面回补，然后重发任务把消息发出去，消费端建单，
 * 而库存已经还回去了 → 超卖；二是订单其实已经建好了、只是 Redis 结论没写上，
 * 回补会把一张有效订单对应的库存加回去 → 同样超卖。
 *
 * <h3>为什么"已发送"要重发而不是直接回补</h3>
 * 消息在 broker 上，消费端可能只是暂时跟不上（MySQL 慢、实例重启）。
 * 直接回补的话，消费端随后消费成功建了订单，而库存与资格都已归还——
 * 用户白得一单，库存少一份。所以先重发（幂等，最多多建一次尝试），
 * 连续 {@code orphan-rounds-before-rollback} 轮还是没结论才认定消息真的丢了。
 * 轮次计数存 Redis 而不是内存：job 实例重启后计数不能归零，否则永远凑不满 N 轮。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualificationReconciler {

    private final SeckillActivityMapper    activityMapper;
    private final SeckillGoodsMapper       goodsMapper;
    private final OrderMapper              orderMapper;
    private final MqMessageMapper          mqMapper;
    private final SeckillExecutor          executor;
    private final SeckillCompensateService compensateService;
    private final ReliableMqProducer       producer;
    private final StockRollbackFallback    rollbackFallback;
    private final StringRedisTemplate      redis;
    private final ReconcileRecorder        recorder;
    private final SeckillMetrics           metrics;
    private final AlarmService             alarm;
    private final FssProperties            props;

    /** @return 本轮处理的孤儿数 */
    public int reconcile() {
        var cfg = props.getReconcile();
        LocalDateTime before = LocalDateTime.now().minus(cfg.getOrphanAfter());
        // 近期结束的活动也要扫：关单、回补都可能发生在活动结束之后
        List<Long> activityIds = activityMapper.selectRunningOrRecentEnded(
                LocalDateTime.now().minusHours(2));

        int handled = 0;
        for (Long activityId : activityIds) {
            for (Long skuId : goodsMapper.selectSkuIds(activityId)) {
                try {
                    handled += scanOne(activityId, skuId, before, cfg.getBatchSize());
                } catch (Exception e) {
                    metrics.jobError("reconcile-qualification");
                    log.error("stage=RECONCILE_QUAL activityId={} skuId={} result=ERROR",
                            activityId, skuId, e);
                }
            }
        }
        return handled;
    }

    private int scanOne(long activityId, long skuId, LocalDateTime before, int limit) {
        String pattern = RedisKeys.request(activityId, skuId, "*");
        int handled = 0;
        try (Cursor<String> c = redis.scan(ScanOptions.scanOptions()
                .match(pattern).count(200).build())) {
            while (c.hasNext() && handled < limit) {
                String key = c.next();
                Map<Object, Object> h = redis.opsForHash().entries(key);
                if (h.isEmpty() || !"0".equals(String.valueOf(h.get("status")))) {
                    continue;               // 只关心排队中的
                }
                if (!isOldEnough(h.get("ts"), before)) {
                    continue;               // 还在正常处理窗口内，给异步链路留时间
                }
                String requestNo = key.substring(key.lastIndexOf(':') + 1);
                handleOrphan(activityId, skuId, requestNo, h);
                handled++;
            }
        }
        return handled;
    }

    /**
     * {@code ts} 是脚本 A 用 Redis {@code TIME} 写进去的 epoch millis。
     *
     * <p>解析不出来时返回 {@code true}（当成"够老了"）：一条 ts 坏掉的记录如果被当成
     * "还很新"，就永远不会被处理，而它恰恰是最需要人看一眼的那种数据。
     */
    private boolean isOldEnough(Object ts, LocalDateTime before) {
        if (ts == null) {
            return true;
        }
        try {
            long millis = Long.parseLong(String.valueOf(ts));
            LocalDateTime t = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault());
            return t.isBefore(before);
        } catch (Exception e) {
            return true;
        }
    }

    private void handleOrphan(long activityId, long skuId, String requestNo,
                              Map<Object, Object> h) {
        long userId = parseLong(h.get("userId"));
        String traceId = h.get("traceId") == null ? null : String.valueOf(h.get("traceId"));
        TraceContext.set(traceId);
        try {
            // 先看订单是不是其实已经建好了——只是 Redis 结论回写失败。
            // 这一步必须在任何回补之前：回补一张有效订单的库存就是超卖
            Order order = orderMapper.selectByRequestNo(requestNo);
            if (order != null) {
                executor.writeResult(activityId, skuId, requestNo, order.getUserId(),
                        SeckillRequestStatus.SUCCESS, order.getOrderNo(), null);
                recorder.record(ReconcileTaskType.QUALIFICATION, requestNo, activityId, skuId,
                        detail("RESULT_WRITE_LOST", requestNo, order.getOrderNo(), null),
                        ReconcileTaskStatus.AUTO_FIXED, "订单已存在，已补写 Redis 结论");
                log.warn("stage=RECONCILE_QUAL requestNo={} verdict=ORDER_EXISTS 已补写结论",
                        requestNo);
                return;
            }

            MqMessage rec = mqMapper.selectByBizKeyAndTopic(requestNo, MqTopics.ORDER_CREATE);
            if (rec == null) {
                // 连消息记录都没有 → 主链路在落库前就崩了 → 没人会建这张订单
                rollback(activityId, skuId, userId, requestNo, traceId,
                        "无消息记录，主链路在登记前中断");
                return;
            }

            int status = rec.getStatus();
            if (status == MqStatus.PENDING.code()) {
                // 重发任务还在努力，插手只会重复回补。它的退避总时长约 15 分钟，
                // 之后 onSendGiveUp 会回补，不需要对账代劳
                log.info("stage=RECONCILE_QUAL requestNo={} verdict=WAIT_RESEND msgStatus=0",
                        requestNo);
                return;
            }
            if (status == MqStatus.FAILED.code()) {
                // 重发已放弃，onSendGiveUp 应该回补过了。它没回补上才会走到这里，
                // 补一次（脚本 B 幂等）
                rollback(activityId, skuId, userId, requestNo, traceId,
                        "消息投递已放弃且未回补成功");
                return;
            }

            // 已发送 / 已消费但没有订单：消息可能丢在消费端。重发一次，
            // 连续 N 轮仍无结果才认定真丢了
            long rounds = bumpRounds(requestNo);
            int threshold = props.getReconcile().getOrphanRoundsBeforeRollback();
            if (rounds < threshold) {
                producer.doSend(rec);
                log.warn("stage=RECONCILE_QUAL requestNo={} verdict=RESEND round={}/{}",
                        requestNo, rounds, threshold);
                return;
            }
            rollback(activityId, skuId, userId, requestNo, traceId,
                    "消息已发送但连续 " + rounds + " 轮无结果");
        } catch (Exception e) {
            log.error("stage=RECONCILE_QUAL requestNo={} result=ERROR", requestNo, e);
        } finally {
            TraceContext.clear();
        }
    }

    private void rollback(long activityId, long skuId, long userId, String requestNo,
                          String traceId, String reason) {
        OrderCreateMessage msg = OrderCreateMessage.builder()
                .requestNo(requestNo)
                .userId(userId)
                .activityId(activityId)
                .skuId(skuId)
                // 限购固定为 1（docs/08 已知限制 1）。真要支持 >1 时这里必须从
                // t_seckill_request 或消息体读真实数量，写死会回补错数量
                .quantity(1)
                .traceId(traceId)
                .version(OrderCreateMessage.CURRENT_VERSION)
                .build();
        RollbackOutcome outcome = compensateService.rollback(msg, ErrorCode.SYSTEM_BUSY, reason);
        // ALREADY_DONE（幂等命中）也算修好了 —— 之前用 boolean 时它和"回补失败"
        // 无法区分，于是一次正常的重复回补会被记成 NEED_MANUAL，
        // 在工单列表里造出一条根本不存在的待办
        boolean fixed = !outcome.isFailed();
        if (!fixed) {
            rollbackFallback.publish(msg, ErrorCode.SYSTEM_BUSY, reason);
        }
        recorder.record(ReconcileTaskType.QUALIFICATION, requestNo, activityId, skuId,
                detail("ORPHAN_ROLLBACK", requestNo, null, reason),
                fixed ? ReconcileTaskStatus.AUTO_FIXED : ReconcileTaskStatus.NEED_MANUAL,
                fixed ? "孤儿资格已回补: " + reason : "回补失败，库存可能泄漏: " + reason);
        if (fixed) {
            alarm.p2(AlarmService.Event.ORPHAN_QUALIFICATION, requestNo, reason);
        } else {
            alarm.p1(AlarmService.Event.ORPHAN_QUALIFICATION, requestNo,
                    "孤儿资格回补失败，库存泄漏: " + reason);
        }
        log.warn("stage=RECONCILE_QUAL requestNo={} verdict=ROLLBACK outcome={} reason={}",
                requestNo, outcome, reason);
        clearRounds(requestNo);
    }

    /**
     * 轮次计数。
     *
     * <p>存 Redis 而不是内存 Map：job 实例重启后内存计数归零，于是"连续 3 轮"
     * 永远凑不满，孤儿会被无限重发——每分钟一次，直到活动数据过期。
     * TTL 1 小时既够凑满轮次，又能保证计数最终自己消失。
     */
    private long bumpRounds(String requestNo) {
        try {
            String key = RedisKeys.orphanCount(requestNo);
            Long n = redis.opsForValue().increment(key);
            redis.expire(key, Duration.ofHours(1));
            return n == null ? 1 : n;
        } catch (Exception e) {
            // 计数失败时返回 1（"这是第一轮"）：宁可多重发几次，也不要因为读不到计数
            // 就直接回补——回补方向错了就是超卖
            log.warn("stage=RECONCILE_QUAL 轮次计数失败 requestNo={}", requestNo, e);
            return 1;
        }
    }

    private void clearRounds(String requestNo) {
        try {
            redis.delete(RedisKeys.orphanCount(requestNo));
        } catch (Exception ignored) {
            // 计数有 TTL，删不掉也会自己过期
        }
    }

    private static long parseLong(Object o) {
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Map<String, Object> detail(String type, String requestNo,
                                             String orderNo, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("requestNo", requestNo);
        m.put("orderNo", orderNo);
        m.put("reason", reason);
        return m;
    }
}
