package com.fss.reconcile;

import com.fss.biz.job.ReconcileJob;
import com.fss.biz.payment.model.PayCreateCmd;
import com.fss.biz.payment.model.PayCreateVO;
import com.fss.biz.payment.service.PaymentService;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.common.enums.OrderStatus;
import com.fss.common.enums.PayStatus;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.domain.entity.ReconcileTask;
import com.fss.domain.mapper.ReconcileTaskMapper;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.redis.RedisKeys;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三类对账任务（阶段四）。对应 docs/08 的 C14「对账收敛」。
 *
 * <p>对账是"上面七层全都失效时"的最后一道防线，所以这些用例的输入都是<b>人为制造的
 * 不一致</b>——直接改 Redis 或直接改 DB，绕开所有正常路径。这不是在测正常流程，
 * 而是在测"当正常流程已经出过错时，我们能不能发现并收敛"。
 */
@ActiveProfiles({"test", "consumer", "job"})
class ReconcileTest extends IntegrationTestBase {

    @Autowired ReconcileJob        reconcileJob;
    @Autowired ReconcileTaskMapper taskMapper;
    @Autowired PaymentService      paymentService;
    @Autowired SeckillMetrics      metrics;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate        jdbc;
    @Autowired TestFixture         fixture;

    // ==================================================================
    // 库存对账
    // ==================================================================

    @Test
    @DisplayName("C14 Redis 库存被人为改偏 + 无排队请求 → 自动修正")
    void 库存漂移自动修正() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        fixture.submitAndAwait(act, fixture.createUser());
        // 此时 DB available = 9、Redis = 9、queueing = 0

        // 人为把 Redis 改成 5，模拟"Redis 少了 4 份"（少卖方向）
        redis.opsForValue().set(RedisKeys.stock(act.activityId(), act.skuId()), "5");

        reconcileJob.reconcileStock();

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("DB 自洽且没有排队中请求时，才允许按 DB 覆盖 Redis")
                .isEqualTo(9L);
        ReconcileTask task = taskOf(ReconcileTaskType.STOCK, stockBizNo(act));
        assertThat(task.getStatus()).isEqualTo(ReconcileTaskStatus.AUTO_FIXED.code());
        assertThat(task.getDetail()).contains("REDIS_DRIFT_FIXED");
    }

    @Test
    @DisplayName("Redis 库存 > DB 可售 → P1，且绝不自动覆盖")
    void Redis大于DB判P1() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        fixture.submitAndAwait(act, fixture.createUser());

        // Redis 比 DB 多 —— 方向是超卖，不能自动"修"（怎么修都可能已经多发了资格）
        redis.opsForValue().set(RedisKeys.stock(act.activityId(), act.skuId()), "20");

        reconcileJob.reconcileStock();

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("Redis > DB 时不能自动覆盖：覆盖只是把症状抹掉，"
                        + "而多发出去的资格已经在别人手里了")
                .isEqualTo(20L);
        ReconcileTask task = taskOf(ReconcileTaskType.STOCK, stockBizNo(act));
        assertThat(task.getStatus()).isEqualTo(ReconcileTaskStatus.NEED_MANUAL.code());
        assertThat(task.getDetail()).contains("REDIS_GT_DB");
        assertThat(metrics.counterValue("fss_alarm_total", "severity", "P1",
                "event", "STOCK_REDIS_GT_DB")).isGreaterThan(0);
    }

    @Test
    @DisplayName("DB 库存等式被破坏 → P1，且不因此去覆盖 Redis")
    void DB自身不自洽() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        fixture.submitAndAwait(act, fixture.createUser());

        // 人为破坏 total = available + locked + sold
        jdbc.update("UPDATE t_seckill_goods SET available_stock = 3 "
                + "WHERE activity_id = ?", act.activityId());
        // Redis 跟着改成"看起来和 DB 一致"，把注意力逼到等式 1 上
        redis.opsForValue().set(RedisKeys.stock(act.activityId(), act.skuId()), "3");

        reconcileJob.reconcileStock();

        assertThat(taskOf(ReconcileTaskType.STOCK, stockBizNo(act)).getDetail())
                .contains("IDENTITY_BROKEN");
        assertThat(metrics.counterValue("fss_alarm_total", "severity", "P1",
                "event", "STOCK_IDENTITY_BROKEN")).isGreaterThan(0);
    }

    @Test
    @DisplayName("有排队中请求时，Redis 与 DB 的差值是正常的，不该报差异也不该覆盖")
    void 排队中不算差异() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        fixture.submitAndAwait(act, fixture.createUser());
        long before = fixture.redisStock(act.activityId(), act.skuId());

        // 手工造一条"排队中"的请求记录，并让 Redis 库存少一份 —— 这正是
        // 异步化的正常中间态：Lua 扣了，消费端还没落库
        String requestNo = "R-QUEUEING-TEST-" + System.nanoTime();
        String reqKey = RedisKeys.request(act.activityId(), act.skuId(), requestNo);
        redis.opsForHash().putAll(reqKey, java.util.Map.of(
                "status", "0", "userId", "999999",
                "ts", String.valueOf(System.currentTimeMillis())));
        redis.opsForValue().decrement(RedisKeys.stock(act.activityId(), act.skuId()));

        long taskCountBefore = taskMapper.countOpen(ReconcileTaskType.STOCK.code());
        reconcileJob.reconcileStock();

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("有排队中请求时覆盖 Redis 会把那一份又放出去 —— 直接超卖。"
                        + "这是库存对账最容易写错的地方")
                .isEqualTo(before - 1);
        assertThat(taskMapper.countOpen(ReconcileTaskType.STOCK.code()))
                .as("正常的中间态不该被记成差异")
                .isEqualTo(taskCountBefore);

        redis.delete(reqKey);
    }

    @Test
    @DisplayName("库存对账顺带上报四个库存仪表")
    void 库存仪表上报() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        fixture.submitAndAwait(act, fixture.createUser());

        reconcileJob.reconcileStock();

        String a = String.valueOf(act.activityId());
        String s = String.valueOf(act.skuId());
        assertThat(metrics.gaugeValue("fss_stock_remain", "activity", a, "sku", s))
                .isEqualTo(9.0);
        assertThat(metrics.gaugeValue("fss_stock_db_available", "activity", a, "sku", s))
                .isEqualTo(9.0);
        // 叫 fss_stock_capacity 而不是 fss_stock_total：后者导出时会被
        // Prometheus 的 exposition format 剥成 fss_stock（见 MetricsExportTest）
        assertThat(metrics.gaugeValue("fss_stock_capacity", "activity", a, "sku", s))
                .isEqualTo(10.0);
        assertThat(metrics.gaugeValue("fss_stock_queueing", "activity", a, "sku", s))
                .isZero();
    }

    // ==================================================================
    // 资格对账
    // ==================================================================

    @Test
    @DisplayName("孤儿资格：Redis 说排队中但没有订单也没有消息记录 → 回补")
    void 孤儿资格无消息记录() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        // 模拟"Lua 扣完库存，进程在写消息表之前就崩了"
        String requestNo = "R-ORPHAN-" + System.nanoTime();
        String reqKey = RedisKeys.request(act.activityId(), act.skuId(), requestNo);
        redis.opsForHash().putAll(reqKey, java.util.Map.of(
                "status", "0", "userId", String.valueOf(userId),
                "ts", String.valueOf(System.currentTimeMillis() - 600_000)));
        redis.opsForValue().decrement(RedisKeys.stock(act.activityId(), act.skuId()));
        redis.opsForHash().increment(RedisKeys.bought(act.activityId(), act.skuId()),
                String.valueOf(userId), 1);

        reconcileJob.reconcileQualification();

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("没有消息记录 = 没人会建这张订单，库存必须还回去")
                .isEqualTo(10L);
        assertThat(fixture.redisBought(act.activityId(), act.skuId(), userId))
                .as("系统原因的失败，资格要还给用户")
                .isZero();
        assertThat(Integer.parseInt(String.valueOf(
                redis.opsForHash().get(reqKey, "status"))))
                .as("请求要置成已补偿，客户端轮询才能拿到结论而不是一直等")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("订单其实已建成、只是 Redis 结论没写上 → 补写结论，绝不回补")
    void 结论丢失只补结论() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = fixture.submitAndAwait(act, userId);

        // 把结论抹回"排队中"，模拟 writeResult 失败
        String requestNo = vo.getRequestNo();
        String reqKey = RedisKeys.request(act.activityId(), act.skuId(), requestNo);
        redis.opsForHash().put(reqKey, "status", "0");
        redis.opsForHash().put(reqKey, "ts",
                String.valueOf(System.currentTimeMillis() - 600_000));
        long stockBefore = fixture.redisStock(act.activityId(), act.skuId());

        reconcileJob.reconcileQualification();

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("订单是有效的，回补它的库存就是超卖 —— 所以查订单必须在任何回补之前")
                .isEqualTo(stockBefore);
        assertThat(Integer.parseInt(String.valueOf(
                redis.opsForHash().get(reqKey, "status")))).isEqualTo(1);
        assertThat(String.valueOf(redis.opsForHash().get(reqKey, "orderNo")))
                .isEqualTo(vo.getOrderNo());
    }

    // ==================================================================
    // 支付对账
    // ==================================================================

    @Test
    @DisplayName("A 类：支付成功但订单仍待支付 → 自动补推 + locked→sold")
    void 支付成功订单未推进自动修() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = fixture.submitAndAwait(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        // 人为造 A 类：流水成功而订单没跟着推进（模拟回调处理到一半崩了）。
        //
        // finish_time 由<b>Java 侧</b>给一个明确的过去时刻，不用 NOW(3)：
        // 对账的筛选条件是 finish_time < (Java 的 now - payment-diff-after)，
        // 而 NOW(3) 取的是 MySQL 容器的时钟。两个时钟在 Docker Desktop 上能差出
        // 上百毫秒（WSL2 的 VM 时钟会漂），阈值又被测试设成 0s，
        // 于是这条记录选不选中取决于当时的漂移方向——只在整套跑的时候偶发失败
        jdbc.update("UPDATE t_payment SET status = ?, finish_time = ? WHERE pay_no = ?",
                PayStatus.SUCCESS.code(), LocalDateTime.now().minusMinutes(1), pay.getPayNo());

        reconcileJob.reconcilePayment();

        assertThat(orderStatus(vo.getOrderNo())).isEqualTo(OrderStatus.PAID.code());
        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.sold())
                .as("只改订单状态不够：正常支付路径还会做 locked→sold，"
                        + "漏掉它会让 locked 里永久留一份已卖出的量，而库存等式仍然成立")
                .isEqualTo(1);
        assertThat(s.locked()).isZero();
        assertThat(s.identityHolds()).isTrue();

        ReconcileTask task = taskOf(ReconcileTaskType.PAYMENT, pay.getPayNo());
        assertThat(task.getStatus()).isEqualTo(ReconcileTaskStatus.AUTO_FIXED.code());
    }

    @Test
    @DisplayName("D 类：已取消订单出现支付成功 → 人工，绝不自动补推")
    void 已取消订单支付成功转人工() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = fixture.submitAndAwait(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        // 先取消，再人为把流水置成成功 —— 这正是支付与关单竞态的后果
        jdbc.update("UPDATE t_order SET status = ?, cancel_time = NOW(3) WHERE order_no = ?",
                OrderStatus.CANCELLED.code(), vo.getOrderNo());
        jdbc.update("UPDATE t_payment SET status = ?, finish_time = NOW(3) WHERE pay_no = ?",
                PayStatus.SUCCESS.code(), pay.getPayNo());

        reconcileJob.reconcilePayment();

        assertThat(orderStatus(vo.getOrderNo()))
                .as("D 类绝不能补推：关单已经把库存还给别人了，补推等于超卖。"
                        + "设计文档的 A 类 SQL 用 status NOT IN (1,3,4,5) 会把它一起捞进来")
                .isEqualTo(OrderStatus.CANCELLED.code());
        ReconcileTask task = taskOf(ReconcileTaskType.PAYMENT, pay.getPayNo());
        assertThat(task.getStatus()).isEqualTo(ReconcileTaskStatus.NEED_MANUAL.code());
        assertThat(task.getDetail()).contains("PAID_ORDER_CANCELLED");
        assertThat(metrics.counterValue("fss_alarm_total", "severity", "P1",
                "event", "PAYMENT_DIFF")).isGreaterThan(0);
    }

    @Test
    @DisplayName("C 类：金额不一致 → 人工 + P1")
    void 金额不一致转人工() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = fixture.submitAndAwait(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        // 造一条<b>只</b>命中 C 类的记录：订单已支付、有成功流水、但金额不符。
        // 不把订单留在待支付，否则它同时命中 A 类（支付成功 + 订单待支付），
        // 而 A 类会自动补推 —— 那时这个用例到底在测哪一类就取决于时钟漂移了
        jdbc.update("UPDATE t_order SET status = ?, pay_time = ? WHERE order_no = ?",
                OrderStatus.PAID.code(), LocalDateTime.now().minusMinutes(1), vo.getOrderNo());
        jdbc.update("UPDATE t_payment SET status = ?, amount = 0.01, finish_time = ? "
                        + "WHERE pay_no = ?",
                PayStatus.SUCCESS.code(), LocalDateTime.now().minusMinutes(1), pay.getPayNo());

        reconcileJob.reconcilePayment();

        assertThat(taskOf(ReconcileTaskType.PAYMENT, pay.getPayNo()).getDetail())
                .contains("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("同一差异重复发现只留一条未关闭任务，不刷屏")
    void 差异去重() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        fixture.submitAndAwait(act, fixture.createUser());
        redis.opsForValue().set(RedisKeys.stock(act.activityId(), act.skuId()), "20");

        for (int i = 0; i < 3; i++) {
            reconcileJob.reconcileStock();
        }

        long open = taskMapper.selectByType(ReconcileTaskType.STOCK.code(), 50).stream()
                .filter(t -> String.valueOf(t.getBizNo())
                        .equals(act.activityId() + ":" + act.skuId()))
                .filter(t -> t.getStatus() == ReconcileTaskStatus.NEED_MANUAL.code())
                .count();
        assertThat(open)
                .as("每 5 分钟发现同一个差异就插一条 → 一天 288 条，"
                        + "「今天有几个新差异」这个最基本的问题就答不出来了")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------

    /**
     * 取某个业务对象的最新对账任务。
     *
     * <p><b>必须按 bizNo 定位，不能取"最新的一条"</b>：同一个 Spring 上下文里
     * 前面的用例也建过活动，它们同样是进行中状态，同样会被
     * {@code selectActiveGoods()} 捞进这一轮对账。取全表最新的那条时，
     * 断言对象是谁取决于对账的遍历顺序——只在整套跑的时候偶发失败。
     */
    private ReconcileTask taskOf(ReconcileTaskType type, String bizNo) {
        List<ReconcileTask> list = taskMapper.selectByType(type.code(), 200).stream()
                .filter(t -> bizNo.equals(t.getBizNo()))
                .toList();
        assertThat(list).as("应产生 %s 对账任务 bizNo=%s", type, bizNo).isNotEmpty();
        return list.get(0);
    }

    private String stockBizNo(TestFixture.Activity act) {
        return act.activityId() + ":" + act.skuId();
    }

    private int orderStatus(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM t_order WHERE order_no = ?",
                Integer.class, orderNo);
    }

    private PayCreateVO createPay(String orderNo, long userId) {
        PayCreateCmd cmd = new PayCreateCmd();
        cmd.setOrderNo(orderNo);
        return paymentService.create(cmd, userId);
    }
}
