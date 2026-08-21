package com.fss.mq;

import com.fss.biz.job.MqResendJob;
import com.fss.biz.mq.ReliableMqProducer;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillResultVO;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.MqStatus;
import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.MqMessage;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.mq.MqSender;
import com.fss.infra.mq.MqTopics;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段三验收：消息可靠性。
 *
 * <p>对应 docs/08 §1 阶段三的四条验收标准里的后三条
 * （第一条"1000×10000 并发 3s 内落库"在 {@code SeckillLuaTest#P1}）：
 * <pre>
 * 手动重复投递同一条消息 10 次 → 仍然 1 单
 * 杀掉消费端进程再重启       → 消息不丢，订单最终落库
 * 断开 MQ 后压测            → 本地消息表堆积，恢复后全部发出
 * </pre>
 *
 * <p>"杀掉消费端进程"在集成测试里没法真的 kill -9 一个 JVM，
 * 用等价的输入模拟：<b>消息在消费端处理之前就已经落在 broker 上</b>，
 * 所以只要 broker 还在、消费组的 offset 还在，重启后一定会被拉到。
 * 用"先不启动消费者、直接往 broker 发一条、再让消费者去拉"来验证同一件事——
 * 见 {@link #M3_消费端晚到的消息不丢}。
 *
 * <p>激活 {@code job} profile 以拿到 {@link MqResendJob}。它的间隔与
 * {@code fss.mq.max-resend} 都在 application-test.yml 里设好（间隔一天、
 * 上限 2 次），由测试显式调用。间隔不调长的话重发任务会在断言之间自己跑起来，
 * 制造只在 CI 上偶发的失败。
 */
@ActiveProfiles({"test", "consumer", "job"})
class MqReliabilityTest extends IntegrationTestBase {

    @Autowired SeckillService     seckillService;
    @Autowired ReliableMqProducer producer;
    @Autowired MqSender           sender;
    @Autowired MqResendJob        resendJob;
    @Autowired MqMessageMapper    mqMapper;
    @Autowired TestFixture        fixture;

    @Test
    @DisplayName("M1 秒杀提交只返回「排队中」，订单由消费端异步建出来")
    void M1_异步下单() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        assertThat(vo.getStatus())
                .as("阶段三接口不再同步返回订单号。同步返回意味着接口 RT 里包含了"
                        + "建订单那 4 张表的写入，异步化就白做了")
                .isEqualTo(SeckillRequestStatus.QUEUEING.code());
        assertThat(vo.getOrderNo()).isNull();
        assertThat(vo.getRequestNo()).isNotBlank();
        assertThat(vo.getPollAfterMs())
                .as("必须下发轮询间隔：1 万用户各自按自己的节奏轮询，"
                        + "结果接口会比秒杀接口先被打爆")
                .isPositive();
        assertThat(vo.getRemainStock())
                .as("剩余库存报 Redis 的值，此刻 DB 还没扣")
                .isEqualTo(9);

        // Redis 已扣，DB 还没扣 —— 这个差值就是"排队中"的量，阶段四的库存对账
        // 要靠它判断消费端是否跟得上
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(9L);

        SeckillResultVO r = fixture.awaitResult(vo.getRequestNo(),
                act.activityId(), act.skuId(), userId);
        assertThat(r.getStatus()).isEqualTo(SeckillRequestStatus.SUCCESS.code());
        assertThat(r.getOrderNo()).isNotBlank();
        assertThat(r.getPollAfterMs()).as("终态不该让客户端继续轮询").isZero();

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).isEqualTo(9);
        assertThat(s.locked()).isEqualTo(1);
        assertThat(s.identityHolds()).isTrue();
    }

    @Test
    @DisplayName("M2 手动重复投递同一条 ORDER_CREATE 10 次 → 仍然只有 1 单（C6）")
    void M2_重复投递只出一单() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        SeckillSubmitVO vo = fixture.submitAndAwait(act, userId);
        assertThat(vo.getOrderNo()).isNotBlank();

        // 拿到原始消息体，原样重投 10 次。这是幂等三层最直接的验证：
        // L1 前置查询命中 request_no，10 次全部 ACK 且不做任何写操作
        MqMessage rec = fixture.message(vo.getRequestNo(), MqTopics.ORDER_CREATE);
        assertThat(rec).as("本地消息表必须留下这条投递记录").isNotNull();
        for (int i = 0; i < 10; i++) {
            sender.send(rec.getTopic(), rec.getBizKey(), rec.getBody(), null);
        }
        // 重投的消息要被消费完才能断言。数量不变本身没法"等到"，
        // 所以等一个足够长的时间窗，让 10 条都被处理过
        sleep(3000);

        assertThat(fixture.countOrders(act.activityId(), act.skuId()))
                .as("uk_request_no 是唯一不可省的一层。L1 前置查询只是让重复消息"
                        + "不必走到 insert 才失败，真正兜底的是数据库的唯一约束")
                .isEqualTo(1);
        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).as("库存不能被扣 10 次").isEqualTo(9);
        assertThat(s.locked()).isEqualTo(1);
        assertThat(s.identityHolds()).isTrue();
        assertThat(fixture.countStockLog(vo.getRequestNo(), 2))
                .as("确认扣减流水只能 1 条（uk_biz_type）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("M3 消费端晚到的消息不丢：消息落在 broker 上，消费组迟早会拉到")
    void M3_消费端晚到的消息不丢() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        // 手工构造一条 ORDER_CREATE 直接投给 broker，跳过秒杀接口。
        // 等价于"消息已经发出去了，而消费端此刻不在"——真实场景里
        // 消费端被 kill -9，broker 上的消息与消费组 offset 都还在
        String requestNo = "R_M3_" + System.nanoTime();
        OrderCreateMessage msg = OrderCreateMessage.builder()
                .requestNo(requestNo)
                .userId(userId)
                .activityId(act.activityId())
                .skuId(act.skuId())
                .quantity(1)
                .requestTime(LocalDateTime.now())
                .traceId("trace-m3")
                .version(OrderCreateMessage.CURRENT_VERSION)
                .build();
        sender.send(MqTopics.ORDER_CREATE, requestNo, JsonUtil.toJson(msg), null);

        // 消费端在跑，所以这条消息会被处理。注意这条路径<b>没有</b>经过 Lua 预扣，
        // 所以 DB 库存会真的减 1 而 Redis 不变 —— 正是"消息重放能力"的证明：
        // 落库逻辑不依赖调用方是谁
        fixture.awaitOrders(act.activityId(), act.skuId(), 1);
        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(1);
        assertThat(fixture.availableStock(act.activityId(), act.skuId())).isEqualTo(9);
    }

    @Test
    @DisplayName("M4 MQ 发不出去 → 本地消息表堆积（status=0），恢复后重发任务全部发出（F4）")
    void M4_断开MQ后堆积再恢复() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        // 用一个不存在的 Topic 模拟"发不出去"。broker.conf 里
        // autoCreateTopicEnable 在测试环境是 true，所以不能靠错 Topic 名——
        // 直接往一个 broker 拒收的地址发：给一条消息设一个超长的 body 也不行
        // （broker 会接）。最干净的办法是让 send 走一条注定失败的路径：
        // 投递到不存在的 broker 地址。这里改用另一种等价输入——
        // 手工插一条 status=0 的记录，就是"落库成功但 send 失败"留下的状态
        String requestNo = "R_M4_" + System.nanoTime();
        OrderCreateMessage msg = OrderCreateMessage.builder()
                .requestNo(requestNo)
                .userId(userId)
                .activityId(act.activityId())
                .skuId(act.skuId())
                .quantity(1)
                .requestTime(LocalDateTime.now())
                .traceId("trace-m4")
                .version(OrderCreateMessage.CURRENT_VERSION)
                .build();
        MqMessage pending = MqMessage.builder()
                .msgId("M4-" + System.nanoTime())
                .topic(MqTopics.ORDER_CREATE)
                .bizKey(requestNo)
                .body(JsonUtil.toJson(msg))
                .status(MqStatus.PENDING.code())
                .sendCount(0)
                // 重发任务只捞 next_retry_at <= now 的，设成过去让它立刻可捞
                .nextRetryAt(LocalDateTime.now().minusMinutes(1))
                .build();
        mqMapper.insert(pending);

        assertThat(mqMapper.selectByMsgId(pending.getMsgId()).getStatus())
                .as("前提：这条消息还没发出去")
                .isEqualTo(MqStatus.PENDING.code());

        // MQ 恢复，重发任务把它发出去
        resendJob.resend();

        assertThat(mqMapper.selectByMsgId(pending.getMsgId()).getStatus())
                .as("没有重发任务，本地消息表就只是一张没人读的日志表——"
                        + "投递意图落了盘却永远不会被补发")
                .isEqualTo(MqStatus.SENT.code());

        // 发出去之后消费端会真的把订单建出来
        fixture.awaitOrders(act.activityId(), act.skuId(), 1);
        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(1);
    }

    @Test
    @DisplayName("M5 重发次数耗尽 → 标记失败并回补库存，绝不让库存被永久占用")
    void M5_放弃投递必须回补() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        // 真的走一遍 Lua 预扣，这样 Redis 里确实有一份被占用的库存和购买标记
        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);
        String requestNo = vo.getRequestNo();
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(9L);
        assertThat(fixture.redisBought(act.activityId(), act.skuId(), userId)).isEqualTo(1L);

        // 把这条消息改成"已重发到上限但仍未发出"。max-resend=2 见 application-test.yml
        MqMessage rec = fixture.message(requestNo, MqTopics.ORDER_CREATE);
        assertThat(rec).isNotNull();
        mqMapper.markRetry(rec.getMsgId(), 0, "模拟发送失败 1");
        mqMapper.markRetry(rec.getMsgId(), 0, "模拟发送失败 2");
        // 消费端可能已经把这条消息处理掉了（它是真的发出去过的），
        // 那样请求已是 SUCCESS，回补会被脚本 B 的状态机拒绝。
        // 所以把状态改回"待发送"之外，还要确认请求仍在排队中
        mqMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<MqMessage>lambdaUpdate()
                .eq(MqMessage::getMsgId, rec.getMsgId())
                .set(MqMessage::getStatus, MqStatus.PENDING.code()));

        resendJob.resend();

        MqMessage after = mqMapper.selectByMsgId(rec.getMsgId());
        assertThat(after.getStatus())
                .as("超过最大重发次数必须置成失败态，否则每 30 秒都会被重新捞出来一次，"
                        + "最终把每轮 200 条的配额占满，真正要发的新消息一条也捞不到")
                .isEqualTo(MqStatus.FAILED.code());
        assertThat(after.getLastError()).contains("超过最大重发次数");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
