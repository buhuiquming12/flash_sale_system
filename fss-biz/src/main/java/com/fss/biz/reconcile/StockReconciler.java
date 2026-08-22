package com.fss.biz.reconcile;

import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.domain.entity.SeckillGoods;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存对账。整个系统最后一道防线——它要能发现前面七层全都失效的情况。
 *
 * <h3>三条等式，各查一件事（与设计文档的偏差）</h3>
 * docs/05 §9.2 给的判据是
 * <pre>
 * dbOk    = available + occupied + queueing == total
 * redisOk = redisStock == total - occupied - queueing
 * </pre>
 * 第一条是错的：{@code queueing} 是"Redis 已预扣但还没落库"的量，
 * <b>它根本还没到 DB</b>。把它加进 DB 的等式里，只要有排队中请求 {@code dbOk} 就恒为假，
 * 于是每轮都判"DB 漂移"、自动修正永远不触发（自动修正的前提是 {@code dbOk}）、
 * 每 5 分钟刷一条"需人工"。症状是"对账任务一直在报差异，但库存其实是对的"。
 *
 * <p>实现拆成三条相互独立的等式，每条只回答一个问题：
 * <table>
 *   <tr><th>#</th><th>等式</th><th>它能发现什么</th></tr>
 *   <tr><td>1</td><td>{@code total == available + locked + sold}</td>
 *       <td>商品表<b>自身</b>不自洽。这是恒等式，破了必然是某段 UPDATE 写错了</td></tr>
 *   <tr><td>2</td><td>{@code locked + sold == Σ 未取消订单.quantity}</td>
 *       <td>商品表与订单表<b>互不吻合</b>。破了说明有订单没扣库存，或扣了库存没订单</td></tr>
 *   <tr><td>3</td><td>{@code redisStock == available - queueing}</td>
 *       <td>Redis 与 DB 的视图差。这是唯一<b>允许</b>有差值的一条，差值就是排队中的量</td></tr>
 * </table>
 * 拆开的好处是差异定位直接落到某一层，而合成一条只能告诉你"哪里不对"。
 *
 * <h3>{@code redisStock > available} 一定是 P1</h3>
 * Redis 先扣、DB 后扣，所以 Redis 必须<b>小于或等于</b> DB。大于意味着 Redis 里
 * 有一份 DB 不知道的可售库存，会被分配出去 → 超卖。最常见的成因不是并发 bug，
 * 而是<b>活动进行中 Redis key 过期后被重新预热</b>：{@code setIfAbsent} 看到 key
 * 不存在，就用 DB 的 {@code available_stock} 初始化——而那时还有 queueing 份
 * 已经预扣但没落库的量，等于凭空多放了 queueing 个资格（故障用例 F2）。
 *
 * <h3>自动修正的前提必须严格</h3>
 * 只在「DB 两条等式都自洽」<b>且</b>「没有排队中请求」时才允许覆盖 Redis。
 * 有排队中请求时 Redis 与 DB 的差值是<b>正常的</b>，覆盖会把那些正在排队的份额
 * 又放出去一遍，直接造成超卖。这是库存对账最容易写错的地方，
 * 也是"自动修复"这件事本身最危险的地方——修错的方向比不修更糟。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReconciler {

    private final SeckillGoodsMapper  goodsMapper;
    private final OrderMapper         orderMapper;
    private final SeckillExecutor     executor;
    private final StringRedisTemplate redis;
    private final ReconcileRecorder   recorder;
    private final SeckillMetrics      metrics;
    private final AlarmService        alarm;

    /**
     * 对一遍进行中活动的全部秒杀商品。
     *
     * @return 本轮发现的差异条数
     */
    public int reconcile() {
        List<SeckillGoods> goodsList = goodsMapper.selectActiveGoods();
        List<SeckillMetrics.StockView> views = new ArrayList<>(goodsList.size());
        int diffs = 0;

        for (SeckillGoods g : goodsList) {
            try {
                Result r = checkOne(g);
                views.add(r.view());
                diffs += r.diffCount();
            } catch (Exception e) {
                // 单个商品失败不能中断整轮：一条脏数据会让后面所有商品都对不上账
                metrics.jobError("reconcile-stock");
                log.error("stage=RECONCILE_STOCK activityId={} skuId={} result=ERROR",
                        g.getActivityId(), g.getSkuId(), e);
            }
        }

        // 顺手上报库存仪表。对账任务本来就要读这四个值，推给 Micrometer 是零成本的；
        // 反过来让 Gauge 回调去读 Redis/DB 则会让每次 Prometheus 抓取都产生 IO，
        // 而 Redis 一慢就连"Redis 慢了"这条曲线自己都断掉
        metrics.publishStockGauges(views);
        return diffs;
    }

    private Result checkOne(SeckillGoods g) {
        long activityId = g.getActivityId();
        long skuId      = g.getSkuId();

        Long redisStock = executor.currentStock(activityId, skuId);
        int  available  = g.getAvailableStock();
        int  locked     = g.getLockedStock();
        int  sold       = g.getSoldStock();
        int  total      = g.getTotalStock();
        int  occupied   = orderMapper.sumEffectiveQuantity(activityId, skuId);
        int  queueing   = countQueueing(activityId, skuId);

        var view = new SeckillMetrics.StockView(activityId, skuId,
                redisStock == null ? -1 : redisStock, available, queueing, total);
        int diffs = 0;

        // ---- 等式 1：商品表自身自洽。这是恒等式，破了就是某段 UPDATE 写错 ----
        if (total != available + locked + sold) {
            recorder.record(ReconcileTaskType.STOCK, bizNo(activityId, skuId),
                    activityId, skuId,
                    detail("IDENTITY_BROKEN", redisStock, available, locked, sold, total,
                            occupied, queueing),
                    ReconcileTaskStatus.NEED_MANUAL,
                    "total ≠ available + locked + sold，库存账目内部不自洽");
            alarm.p1(AlarmService.Event.STOCK_IDENTITY, bizNo(activityId, skuId),
                    "total=%d available=%d locked=%d sold=%d".formatted(
                            total, available, locked, sold));
            diffs++;
        }

        // ---- 等式 2：商品表与订单表互相吻合 ----
        // locked + sold 是"已经落库的订单占用的量"，它必须等于未取消订单的数量之和。
        // 不等说明两张表有一边漏了：订单建了没扣库存，或扣了库存没建订单
        if (locked + sold != occupied) {
            recorder.record(ReconcileTaskType.STOCK, bizNo(activityId, skuId),
                    activityId, skuId,
                    detail("ORDER_MISMATCH", redisStock, available, locked, sold, total,
                            occupied, queueing),
                    ReconcileTaskStatus.NEED_MANUAL,
                    "locked + sold ≠ 未取消订单数量之和，商品表与订单表不吻合");
            alarm.p1(AlarmService.Event.STOCK_DRIFT, bizNo(activityId, skuId),
                    "locked+sold=%d 订单占用=%d".formatted(locked + sold, occupied));
            diffs++;
        }

        if (redisStock == null) {
            // 未预热或活动结束后 key 已清理。不是差异——它只说明 Redis 侧没有这份数据，
            // 而没有数据就不会分配资格，方向是安全的
            return new Result(view, diffs);
        }

        // ---- Redis > DB：一定是 bug，直接 P1 ----
        if (redisStock > available) {
            recorder.record(ReconcileTaskType.STOCK, bizNo(activityId, skuId),
                    activityId, skuId,
                    detail("REDIS_GT_DB", redisStock, available, locked, sold, total,
                            occupied, queueing),
                    ReconcileTaskStatus.NEED_MANUAL,
                    "Redis 库存大于 DB 可售库存，存在超卖风险。"
                            + "最常见成因是活动进行中 Redis key 过期后被重新预热");
            alarm.p1(AlarmService.Event.STOCK_REDIS_GT_DB, bizNo(activityId, skuId),
                    "redis=%d dbAvailable=%d queueing=%d".formatted(
                            redisStock, available, queueing));
            return new Result(view, diffs + 1);
        }

        if (queueing < 0) {
            // 排队中数量没数出来（SCAN 失败）。此时等式 3 无法求值，而<b>不能当成 0</b>：
            // 那会让自动修正的前提被满足，在信息不全的情况下覆盖 Redis 库存。
            // "Redis ≤ DB" 已经验过，方向是安全的，跳过这一轮等下次
            log.warn("stage=RECONCILE_STOCK activityId={} skuId={} result=QUEUEING_UNKNOWN 跳过 Redis 校验",
                    activityId, skuId);
            return new Result(view, diffs);
        }

        // ---- 等式 3：Redis 与 DB 的差值应恰好等于排队中的量 ----
        long expected = (long) available - queueing;
        if (redisStock == expected) {
            return new Result(view, diffs);
        }

        boolean dbSelfConsistent = total == available + locked + sold && locked + sold == occupied;
        // 自动修正的两个前提，缺一不可。见类注释：有排队中请求时覆盖 Redis 就是超卖
        if (dbSelfConsistent && queueing == 0) {
            redis.opsForValue().set(RedisKeys.stock(activityId, skuId),
                    String.valueOf(available));
            recorder.record(ReconcileTaskType.STOCK, bizNo(activityId, skuId),
                    activityId, skuId,
                    detail("REDIS_DRIFT_FIXED", redisStock, available, locked, sold, total,
                            occupied, queueing),
                    ReconcileTaskStatus.AUTO_FIXED,
                    "Redis 库存已按 DB 可售库存修正: %d → %d".formatted(redisStock, available));
            log.warn("stage=RECONCILE_STOCK activityId={} skuId={} action=AUTO_FIX {} → {}",
                    activityId, skuId, redisStock, available);
            return new Result(
                    new SeckillMetrics.StockView(activityId, skuId, available, available,
                            queueing, total),
                    diffs + 1);
        }

        recorder.record(ReconcileTaskType.STOCK, bizNo(activityId, skuId),
                activityId, skuId,
                detail("REDIS_DRIFT", redisStock, available, locked, sold, total,
                        occupied, queueing),
                ReconcileTaskStatus.NEED_MANUAL,
                dbSelfConsistent
                        ? "Redis 与 DB 差值不等于排队中数量，但仍有排队请求，不敢自动覆盖"
                        : "Redis 漂移且 DB 本身不自洽，必须先修 DB");
        alarm.p2(AlarmService.Event.STOCK_DRIFT, bizNo(activityId, skuId),
                "redis=%d expected=%d queueing=%d".formatted(redisStock, expected, queueing));
        return new Result(view, diffs + 1);
    }

    /**
     * 数一遍 Redis 里"排队中"的请求。
     *
     * <p><b>用 SCAN 而不是 KEYS</b>：KEYS 会阻塞 Redis 整个主线程，遍历几十万个 key
     * 期间所有秒杀请求都在等——为了对账把线上打挂，得不偿失。
     * SCAN 是渐进式的，单次只返回一批。
     *
     * <p><b>只数 status = 0。</b> 终态的请求（成功、失败、已补偿）都不再占用库存。
     *
     * <p>已知成本：MATCH 模式在 Redis 侧是<b>先取回一批 key 再过滤</b>，
     * 所以扫描量与库里的总 key 数成正比，而不是与匹配数成正比。活动与 SKU 很多时
     * 这会变贵，替代方案是从 {@code t_seckill_request} 反向查——但那需要主链路
     * 同步写库，等于把异步化的收益还回去一部分。当前量级下 SCAN 更划算。
     */
    private int countQueueing(long activityId, long skuId) {
        String pattern = RedisKeys.request(activityId, skuId, "*");
        int count = 0;
        try (Cursor<String> c = redis.scan(ScanOptions.scanOptions()
                .match(pattern).count(200).build())) {
            while (c.hasNext()) {
                Object status = redis.opsForHash().get(c.next(), "status");
                if (status != null && "0".equals(String.valueOf(status))) {
                    count++;
                }
            }
        } catch (Exception e) {
            // 数不出来时返回 -1 让上层知道"这个数不可信"，而不是返回 0 —— 返回 0
            // 会让自动修正的 queueing == 0 前提被满足，于是在信息不全的情况下
            // 覆盖 Redis 库存，可能直接超卖
            log.warn("stage=RECONCILE_STOCK 统计排队中失败 activityId={} skuId={}",
                    activityId, skuId, e);
            return -1;
        }
        return count;
    }

    private static String bizNo(long activityId, long skuId) {
        return activityId + ":" + skuId;
    }

    private static Map<String, Object> detail(String type, Long redisStock, int available,
                                              int locked, int sold, int total,
                                              int occupied, int queueing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("redisStock", redisStock);
        m.put("dbAvailable", available);
        m.put("dbLocked", locked);
        m.put("dbSold", sold);
        m.put("dbTotal", total);
        m.put("orderOccupied", occupied);
        m.put("queueing", queueing);
        return m;
    }

    private record Result(SeckillMetrics.StockView view, int diffCount) {
    }
}
