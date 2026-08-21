package com.fss.test;

import com.fss.biz.activity.model.ActivityCreateCmd;
import com.fss.biz.activity.model.SeckillGoodsCmd;
import com.fss.biz.activity.service.ActivityService;
import com.fss.biz.activity.service.WarmupService;
import com.fss.biz.product.model.ProductCreateCmd;
import com.fss.biz.product.model.SkuCreateCmd;
import com.fss.biz.product.service.ProductService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillResultVO;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.ActivityStatus;
import com.fss.common.enums.MqStatus;
import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.domain.entity.MqMessage;
import com.fss.domain.entity.Order;
import com.fss.domain.entity.User;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.domain.mapper.UserMapper;
import com.fss.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试数据装配。
 *
 * <p>用户直接插库而不走 {@code UserService.register}：BCrypt 强度 10 单次约 50ms，
 * 造 500 个用户就是 25 秒，而这些测试要验的是秒杀并发，不是密码哈希。
 *
 * <p>阶段二起 {@code createRunningActivity} 会顺带完成 Redis 预热 ——
 * 没有预热，Lua 读不到 {@code seckill:goods}，所有秒杀请求都返回"未预热"。
 * 预热必须发生在改完活动时间窗口<b>之后</b>，见 {@link #createActivity}。
 */
@Component
@RequiredArgsConstructor
public class TestFixture {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100_000);

    /** 固定的合法 BCrypt 摘要，对应密码 "Passw0rd!" 之外的任意值都无所谓——测试不登录 */
    private static final String PWD =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserMapper            userMapper;
    private final ProductService        productService;
    private final ActivityService       activityService;
    private final WarmupService         warmupService;
    private final SeckillService        seckillService;
    private final SeckillActivityMapper activityMapper;
    private final OrderMapper           orderMapper;
    private final MqMessageMapper       mqMapper;
    private final StringRedisTemplate   redis;
    private final JdbcTemplate          jdbc;

    public long createAdmin() {
        return createUser(1);
    }

    public long createUser() {
        return createUser(0);
    }

    private long createUser(int role) {
        long n = SEQ.incrementAndGet();
        User u = User.builder()
                .username("u" + n)
                .password(PWD)
                .nickname("用户" + n)
                .role(role)
                .status(1)
                .build();
        userMapper.insert(u);
        return u.getId();
    }

    public List<Long> createUsers(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(createUser());
        }
        return ids;
    }

    /** 创建一个已在进行中的活动，返回 (activityId, skuId) */
    public Activity createRunningActivity(int stock) {
        return createActivity(stock, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1), true);
    }

    public Activity createNotStartedActivity(int stock) {
        return createActivity(stock, LocalDateTime.now().plusHours(1),
                LocalDateTime.now().plusHours(2), true);
    }

    public Activity createEndedActivity(int stock) {
        return createActivity(stock, LocalDateTime.now().minusHours(2),
                LocalDateTime.now().minusHours(1), true);
    }

    /**
     * @param start 可以是过去时间。发布校验要求"开始时间必须晚于当前时间"，
     *              所以这里先按未来时间发布，再直接改库把时间调到过去——
     *              这比为了测试放宽生产校验规则要好。
     */
    private Activity createActivity(int stock, LocalDateTime start,
                                    LocalDateTime end, boolean publish) {
        long adminId = createAdmin();

        ProductCreateCmd p = new ProductCreateCmd();
        p.setTitle("测试商品 " + SEQ.incrementAndGet());
        p.setMainImage("https://example.com/img.png");
        p.setStatus(1);
        long productId = productService.createProduct(p, adminId);

        SkuCreateCmd s = new SkuCreateCmd();
        s.setProductId(productId);
        s.setSpec("黑色/256G");
        s.setPrice(new BigDecimal("7999.00"));
        s.setStock(Math.max(stock, 1) * 10);
        s.setStatus(1);
        long skuId = productService.createSku(s, adminId);

        SeckillGoodsCmd g = new SeckillGoodsCmd();
        g.setSkuId(skuId);
        g.setSeckillPrice(new BigDecimal("4999.00"));
        g.setTotalStock(stock);
        g.setLimitPerUser(1);

        ActivityCreateCmd a = new ActivityCreateCmd();
        a.setName("测试活动 " + SEQ.incrementAndGet());
        // 先用一个满足发布校验的未来时间
        a.setStartTime(LocalDateTime.now().plusDays(1));
        a.setEndTime(LocalDateTime.now().plusDays(2));
        a.setGoods(List.of(g));
        long activityId = activityService.create(a, adminId);

        if (publish) {
            activityService.publish(activityId, adminId);
            // 调整到目标时间窗口并推进状态
            jdbc.update("UPDATE t_seckill_activity SET start_time = ?, end_time = ?, status = ? "
                            + "WHERE id = ?",
                    start, end, ActivityStatus.RUNNING.code(), activityId);
            // 预热必须在改完时间之后：Lua 的时间窗口判定读的是预热写进 Redis 的
            // startTime / endTime，先预热再改库的话 Redis 里留的是那个"明天开始"的
            // 假时间，所有请求都会被判成"活动未开始"
            warmupService.warmupOne(activityId);
        }
        return new Activity(activityId, skuId, adminId, stock);
    }

    /**
     * 创建一个已发布但<b>未预热</b>的活动。
     *
     * <p>用于验证"没预热就抢不到"（对应故障用例 F13 的另一半：
     * 活动侥幸进了 RUNNING 但 Redis 没数据时，用户得到的是明确的 2005
     * 而不是超卖或系统错误）。
     */
    public Activity createRunningActivityWithoutWarmup(int stock) {
        Activity act = createActivity(stock, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1), true);
        clearRedisKeys(act.activityId(), act.skuId());
        return act;
    }

    /** 抹掉某商品的全部 Redis 痕迹，模拟数据丢失（F2） */
    public void clearRedisKeys(long activityId, long skuId) {
        redis.delete(List.of(
                RedisKeys.goods(activityId, skuId),
                RedisKeys.stock(activityId, skuId),
                RedisKeys.bought(activityId, skuId),
                RedisKeys.released(activityId, skuId)));
    }

    public void warmup(long activityId) {
        warmupService.warmupOne(activityId);
    }

    /** Redis 侧剩余库存；null 表示未预热 */
    public Long redisStock(long activityId, long skuId) {
        String v = redis.opsForValue().get(RedisKeys.stock(activityId, skuId));
        return v == null ? null : Long.parseLong(v);
    }

    /** Redis 侧某用户的已购数量 */
    public long redisBought(long activityId, long skuId, long userId) {
        Object v = redis.opsForHash()
                .get(RedisKeys.bought(activityId, skuId), String.valueOf(userId));
        return v == null ? 0L : Long.parseLong(String.valueOf(v));
    }

    /** Redis 侧商品状态：1 在售 / 2 售罄 / 0 停售；null 表示未预热 */
    public Integer redisGoodsStatus(long activityId, long skuId) {
        Object v = redis.opsForHash().get(RedisKeys.goods(activityId, skuId), "status");
        return v == null ? null : Integer.parseInt(String.valueOf(v));
    }

    /** 直接改 Redis 里的活动时间窗口，用于制造"Redis 时钟与应用时钟不一致" */
    public void overrideRedisWindow(long activityId, long skuId,
                                    LocalDateTime start, LocalDateTime end) {
        String key = RedisKeys.goods(activityId, skuId);
        redis.opsForHash().put(key, "startTime", String.valueOf(toMillis(start)));
        redis.opsForHash().put(key, "endTime", String.valueOf(toMillis(end)));
    }

    private long toMillis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public int availableStock(long activityId, long skuId) {
        return jdbc.queryForObject(
                "SELECT available_stock FROM t_seckill_goods WHERE activity_id = ? AND sku_id = ?",
                Integer.class, activityId, skuId);
    }

    public StockSnapshot stock(long activityId, long skuId) {
        return jdbc.queryForObject("""
                SELECT total_stock, available_stock, locked_stock, sold_stock, released_stock, status
                  FROM t_seckill_goods WHERE activity_id = ? AND sku_id = ?
                """,
                (rs, i) -> new StockSnapshot(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6)),
                activityId, skuId);
    }

    public long countOrders(long activityId, long skuId) {
        return jdbc.queryForObject(
                "SELECT COUNT(1) FROM t_order WHERE activity_id = ? AND sku_id = ?",
                Long.class, activityId, skuId);
    }

    public long countStockLog(String bizNo, int changeType) {
        return jdbc.queryForObject(
                "SELECT COUNT(1) FROM t_stock_log WHERE biz_no = ? AND change_type = ?",
                Long.class, bizNo, changeType);
    }

    public void expireOrder(String orderNo) {
        jdbc.update("UPDATE t_order SET expire_time = ? WHERE order_no = ?",
                LocalDateTime.now().minusMinutes(30), orderNo);
    }

    public record Activity(long activityId, long skuId, long adminId, int stock) {
    }

    // ==================================================================
    // 阶段三：异步化之后的等待helper
    //
    // 秒杀接口不再返回 orderNo，它返回"排队中"。阶段一二写的那些断言
    // （订单数、库存等式、orderNo 非空）依然是对的，只是要等消费端处理完。
    // 所以这里提供两个入口，让那批用例只改一行「submit → submitAndAwait」，
    // 断言本身一个字不动 —— 它们锁的是对外行为，不是某一层的实现。
    // ==================================================================

    /** 异步链路的默认等待上限。本地 MySQL 处理 1000 单约 3~5 秒，给 60 秒余量 */
    private static final Duration AWAIT = Duration.ofSeconds(60);

    /**
     * 提交秒杀并等到有明确结论，返回的 VO 形状与阶段二的同步返回一致。
     *
     * <p><b>失败时抛 {@link BizException}</b>，错误码由请求终态反推。这样
     * {@code assertThatThrownBy(...).extracting(errorCode)} 这类断言在异步化之后
     * 仍然成立：无论拒绝发生在 Lua（同步抛）还是消费端（异步写结论），
     * 对调用者都是同一个错误码。
     *
     * <p>注意 Lua 阶段的拒绝（库存不足、一人一单、时间窗口、未预热）本来就是
     * <b>同步</b>抛出的，走不到这里的轮询——异步化只影响"资格已给出、
     * 但订单还没建"这一段。
     */
    public SeckillSubmitVO submitAndAwait(Activity act, long userId) {
        return submitAndAwait(act.activityId(), act.skuId(), userId);
    }

    public SeckillSubmitVO submitAndAwait(long activityId, long skuId, long userId) {
        return await(seckillService.submit(SeckillCmd.of(activityId, skuId, 1), userId),
                activityId, skuId, userId);
    }

    /** 带令牌（或任何自定义参数）的提交，同样等到有结论 */
    public SeckillSubmitVO submitAndAwaitWithToken(SeckillCmd cmd, long userId) {
        return await(seckillService.submit(cmd, userId),
                cmd.getActivityId(), cmd.getSkuId(), userId);
    }

    private SeckillSubmitVO await(SeckillSubmitVO vo, long activityId, long skuId,
                                  long userId) {
        if (vo.getOrderNo() != null) {
            return vo;                      // 同步链路（阶段二）或已经有结论
        }
        SeckillResultVO r = awaitResult(vo.getRequestNo(), activityId, skuId, userId);

        SeckillRequestStatus s = SeckillRequestStatus.of(r.getStatus());
        if (s != SeckillRequestStatus.SUCCESS) {
            throw new BizException(errorCodeOf(s), r.getFailReason());
        }
        // 补齐 expireTime：客户端拿到 orderNo 之后要显示支付倒计时，
        // 而结果查询接口不返回它（那是订单接口的职责）
        Order order = orderMapper.selectByRequestNo(vo.getRequestNo());
        return SeckillSubmitVO.success(vo.getRequestNo(), r.getOrderNo(),
                vo.getRemainStock() == null ? 0 : vo.getRemainStock(),
                order == null ? null : order.getExpireTime());
    }

    /** 轮询结果接口直到终态。超时即失败——挂着不给结论是阶段三最需要防的故障 */
    public SeckillResultVO awaitResult(String requestNo, long activityId, long skuId,
                                       long userId) {
        long deadline = System.currentTimeMillis() + AWAIT.toMillis();
        SeckillResultVO last = null;
        while (System.currentTimeMillis() < deadline) {
            last = seckillService.queryResult(requestNo, activityId, skuId, userId);
            if (SeckillRequestStatus.of(last.getStatus()).isTerminal()) {
                return last;
            }
            sleep(50);
        }
        throw new AssertionError("等待秒杀结论超时 requestNo=" + requestNo + " last=" + last);
    }

    /**
     * 等到该商品的订单数达到预期。
     *
     * <p>并发用例用这个而不是逐个 {@link #awaitResult}：1 万个请求各自轮询会把
     * 结果接口打成新的瓶颈，测出来的耗时全是轮询的开销。
     *
     * <p><b>轮询而不是固定 sleep。</b> 固定 sleep 在 CI 上必然随机失败：
     * 给短了消费端还没处理完，给长了每个用例白等几秒、几十个用例就是几分钟。
     *
     * @return 达到预期所用的毫秒数，<b>不含</b>末尾那段确认等待。用于算消费吞吐
     */
    public long awaitOrders(long activityId, long skuId, long expected) {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + AWAIT.toMillis();
        long actual = 0;
        while (System.currentTimeMillis() < deadline) {
            actual = countOrders(activityId, skuId);
            if (actual >= expected) {
                long elapsed = System.currentTimeMillis() - t0;
                // 再等一小会儿：只要 >= 就立刻返回的话，"恰好 1000 单"的断言会在
                // 第 1000 单刚落库时通过，而此时第 1001 单（如果真有超卖 bug）
                // 可能还在路上 —— 断言就白写了
                sleep(300);
                return elapsed;
            }
            sleep(50);
        }
        throw new AssertionError("等待订单落库超时 expected=" + expected + " actual=" + actual);
    }

    /**
     * 等到该订单的 Redis 库存回补生效。
     *
     * <p>阶段三把 Redis 回补也挪到了消息里（见 {@code StockReleaseService}），
     * 所以 {@code cancel} 返回时 Redis 还没加回来。
     */
    public void awaitRedisStock(long activityId, long skuId, long expected) {
        long deadline = System.currentTimeMillis() + AWAIT.toMillis();
        Long actual = null;
        while (System.currentTimeMillis() < deadline) {
            actual = redisStock(activityId, skuId);
            if (expected == (actual == null ? -1L : actual)) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("等待 Redis 库存超时 expected=" + expected + " actual=" + actual);
    }

    /** 等到本地消息表里这条业务键的消息被标成已发送 */
    public void awaitMessageSent(String bizKey, String topic) {
        long deadline = System.currentTimeMillis() + AWAIT.toMillis();
        Integer status = null;
        while (System.currentTimeMillis() < deadline) {
            MqMessage rec = mqMapper.selectByBizKeyAndTopic(bizKey, topic);
            status = rec == null ? null : rec.getStatus();
            if (status != null && status >= MqStatus.SENT.code()) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("等待消息发出超时 bizKey=" + bizKey + " status=" + status);
    }

    public MqMessage message(String bizKey, String topic) {
        return mqMapper.selectByBizKeyAndTopic(bizKey, topic);
    }

    /**
     * 请求终态 → 错误码。
     *
     * <p>{@code COMPENSATED} 映射到 {@code SYSTEM_BUSY} 而不是某个具体业务码：
     * 它的含义就是"系统原因导致失败，库存与资格都已退回"。
     */
    private ErrorCode errorCodeOf(SeckillRequestStatus s) {
        return switch (s) {
            case STOCK_NOT_ENOUGH -> ErrorCode.STOCK_NOT_ENOUGH;
            case ALREADY_BOUGHT   -> ErrorCode.ALREADY_BOUGHT;
            case COMPENSATED      -> ErrorCode.SYSTEM_BUSY;
            default               -> ErrorCode.SYSTEM_ERROR;
        };
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }

    /**
     * @param total total_stock 应恒等于 available + locked + sold
     */
    public record StockSnapshot(int total, int available, int locked,
                                int sold, int released, int status) {
        public boolean identityHolds() {
            return total == available + locked + sold;
        }
    }
}
