package com.fss.test;

import com.fss.biz.activity.model.ActivityCreateCmd;
import com.fss.biz.activity.model.SeckillGoodsCmd;
import com.fss.biz.activity.service.ActivityService;
import com.fss.biz.activity.service.WarmupService;
import com.fss.biz.product.model.ProductCreateCmd;
import com.fss.biz.product.model.SkuCreateCmd;
import com.fss.biz.product.service.ProductService;
import com.fss.common.enums.ActivityStatus;
import com.fss.domain.entity.User;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.domain.mapper.UserMapper;
import com.fss.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
    private final SeckillActivityMapper activityMapper;
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
