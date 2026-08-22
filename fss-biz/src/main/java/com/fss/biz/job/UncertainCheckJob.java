package com.fss.biz.job;

import com.fss.biz.mq.ReliableMqProducer;
import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.biz.seckill.core.UncertainRecorder;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.ReconcileTask;
import com.fss.domain.mapper.ReconcileTaskMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.DistributedLock;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 不确定结果确认（{@code checkUncertain}）。
 *
 * <p>处理"Redis 脚本调用超时，不知道库存扣没扣"这一类请求。判据只有一条，
 * 但它足够：脚本 A 的原子性保证「预扣库存」与「写 req key」要么都发生要么都不发生，
 * 所以
 * <pre>
 * EXISTS seckill:req:{a:s}:&lt;requestNo&gt; == 1  ⟺  库存已扣
 * </pre>
 * 存在 → 脚本执行过且成功预扣 → <b>补发消息</b>，用户的订单照样能建出来；
 * 不存在 → 脚本没执行 → 库存没动 → 什么都不用做。
 *
 * <h3>三个容易写错的地方</h3>
 * <ol>
 *   <li><b>不能立刻判定。</b> 超时的那一刻脚本可能正在执行。所以只取
 *       {@code uncertain-settle}（默认 5 秒）之前登记的记录。少了这个静置窗口，
 *       "刚超时就判定没执行"会把一份已扣的库存留成永久泄漏。</li>
 *   <li><b>判定失败要留着记录，不能删。</b> {@code EXISTS} 本身也可能失败
 *       （Redis 还没恢复）。此时删掉记录就等于丢掉了"库存可能已扣"的唯一线索。
 *       留着下轮再试，反正判定与补发都是幂等的。</li>
 *   <li><b>补发用同一个 requestNo。</b> 换新的会绕过全部幂等层：
 *       req key 上写着旧 requestNo 的预扣，新消息建出的订单是另一条记录，
 *       于是一次预扣变成两张订单。{@code uk_request_no} 也拦不住——它们的
 *       request_no 本来就不同。</li>
 * </ol>
 *
 * <p>超过保留期（默认 10 分钟）仍无法判定的记录转人工：落一条资格类对账任务。
 * 无限保留会让这个集合在 Redis 长期不可用时无界增长，而那时集合里的每条记录
 * 都已经无法判定了。
 */
@Slf4j
@Component
@Profile("job")
@RequiredArgsConstructor
public class UncertainCheckJob {

    private final UncertainRecorder   recorder;
    private final SeckillExecutor     executor;
    private final ReliableMqProducer  producer;
    private final ReconcileTaskMapper reconcileMapper;
    private final SeckillMetrics      metrics;
    private final AlarmService        alarm;
    private final FssProperties       props;

    @Scheduled(fixedDelayString = "${fss.job.uncertain-check-delay-ms:10000}")
    @DistributedLock(key = "uncertain-check", leaseSeconds = 60)
    public void checkUncertain() {
        var cfg = props.getReconcile();
        var records = recorder.take(cfg.getBatchSize(), cfg.getUncertainSettle());
        if (records.isEmpty()) {
            return;
        }

        int executed = 0;
        int notExecuted = 0;
        int expired = 0;
        int undecided = 0;

        for (var rec : records) {
            OrderCreateMessage msg = rec.msg();
            TraceContext.set(msg.getTraceId());
            try {
                boolean exists = executor.requestExists(
                        msg.getActivityId(), msg.getSkuId(), msg.getRequestNo());
                if (exists) {
                    // 脚本执行过、库存已扣 → 补发消息。用同一个 requestNo，
                    // 所以哪怕原来那条消息其实也发出去了，L1/L2 幂等会挡住第二张订单
                    producer.sendReliable(MqTopics.ORDER_CREATE, msg.getRequestNo(), msg, null);
                    executed++;
                    log.warn("stage=UNCERTAIN_CHECK requestNo={} verdict=EXECUTED 已补发消息",
                            msg.getRequestNo());
                } else {
                    notExecuted++;
                    log.info("stage=UNCERTAIN_CHECK requestNo={} verdict=NOT_EXECUTED 无需处理",
                            msg.getRequestNo());
                }
                recorder.remove(rec.raw());
            } catch (Exception e) {
                // 判定或补发失败。记录必须留着——删掉就丢了"库存可能已扣"的唯一线索。
                // 但不能无限留：超过保留期转人工
                if (isExpired(rec.recordedAt(), cfg.getUncertainRetention())) {
                    expired++;
                    toManual(msg, e);
                    recorder.remove(rec.raw());
                } else {
                    undecided++;
                    log.warn("stage=UNCERTAIN_CHECK requestNo={} verdict=UNDECIDED 保留待下轮",
                            msg.getRequestNo(), e);
                }
            } finally {
                TraceContext.clear();
            }
        }

        log.info("stage=JOB_UNCERTAIN_CHECK executed={} notExecuted={} expired={} undecided={} "
                        + "remaining={}",
                executed, notExecuted, expired, undecided, recorder.size());
    }

    private boolean isExpired(long recordedAt, Duration retention) {
        return System.currentTimeMillis() - recordedAt > retention.toMillis();
    }

    /**
     * 转人工。
     *
     * <p><b>不自动回补。</b> 这里的处境恰好是"不知道库存扣没扣"，
     * 而回补一份根本没扣过的库存就是凭空增加库存 → 直接超卖。
     * 少卖一份可以靠人工修，超卖要赔钱。所以宁可留着差额等库存对账，
     * 也不做方向未知的自动修正。
     */
    private void toManual(OrderCreateMessage msg, Exception cause) {
        try {
            reconcileMapper.insert(ReconcileTask.builder()
                    .taskType(ReconcileTaskType.QUALIFICATION.code())
                    .bizNo(msg.getRequestNo())
                    .activityId(msg.getActivityId())
                    .skuId(msg.getSkuId())
                    .detail(JsonUtil.toJson(msg))
                    .status(ReconcileTaskStatus.NEED_MANUAL.code())
                    .handleResult("Redis 结果长期无法判定: " + cause.getMessage())
                    .build());
            metrics.reconcileDiff("qualification", "need_manual");
        } catch (Exception e) {
            log.error("stage=UNCERTAIN_CHECK requestNo={} result=RECORD_FAILED",
                    msg.getRequestNo(), e);
        }
        alarm.p2(AlarmService.Event.REDIS_UNCERTAIN, msg.getRequestNo(),
                "超过保留期仍无法判定，已转人工（不自动回补：方向未知）");
    }
}
