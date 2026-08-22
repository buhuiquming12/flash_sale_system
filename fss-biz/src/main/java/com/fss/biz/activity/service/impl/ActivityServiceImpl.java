package com.fss.biz.activity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fss.biz.activity.model.ActivityCreateCmd;
import com.fss.biz.activity.model.ActivityDetailVO;
import com.fss.biz.activity.model.ActivityListItemVO;
import com.fss.biz.activity.model.SeckillGoodsCmd;
import com.fss.biz.activity.service.ActivityService;
import com.fss.biz.audit.AdminAuditService;
import com.fss.biz.product.service.ProductService;
import com.fss.common.enums.ActivityStatus;
import com.fss.common.enums.StockChangeType;
import com.fss.common.enums.WarmupState;
import com.fss.common.error.Assert;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.result.PageR;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.Product;
import com.fss.domain.entity.SeckillActivity;
import com.fss.domain.entity.SeckillGoods;
import com.fss.domain.entity.Sku;
import com.fss.domain.entity.StockLog;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.domain.mapper.StockLogMapper;
import com.fss.infra.cache.LogicalExpiryCache;
import com.fss.infra.degrade.DegradeSwitch;
import com.fss.infra.redis.RedisKeys;
import com.fss.infra.tx.TxSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillGoodsMapper    goodsMapper;
    private final StockLogMapper        stockLogMapper;
    private final ProductService        productService;
    private final AdminAuditService     audit;
    private final LogicalExpiryCache    cache;
    private final DegradeSwitch         degradeSwitch;
    private final StringRedisTemplate   redis;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long create(ActivityCreateCmd cmd, long adminId) {
        Assert.require(cmd.getEndTime().isAfter(cmd.getStartTime()), "结束时间必须晚于开始时间");

        SeckillActivity act = SeckillActivity.builder()
                .name(cmd.getName())
                .startTime(cmd.getStartTime())
                .endTime(cmd.getEndTime())
                .status(ActivityStatus.DRAFT.code())
                .warmupState(WarmupState.NONE.code())
                .warmupVersion(0)
                .creatorId(adminId)
                .build();
        activityMapper.insert(act);

        for (SeckillGoodsCmd g : cmd.getGoods()) {
            Sku sku = productService.getSku(g.getSkuId());
            Assert.require(sku != null && sku.getStatus() == 1, ErrorCode.SKU_NOT_FOUND);

            goodsMapper.insert(SeckillGoods.builder()
                    .activityId(act.getId())
                    .skuId(g.getSkuId())
                    .seckillPrice(g.getSeckillPrice())
                    .totalStock(g.getTotalStock())
                    .availableStock(g.getTotalStock())
                    .lockedStock(0)
                    .soldStock(0)
                    .releasedStock(0)
                    .limitPerUser(g.getLimitPerUser())
                    .status(SeckillGoods.STATUS_ON_SALE)
                    .version(0)
                    .build());
        }

        audit.record(adminId, "ACTIVITY_CREATE", "ACTIVITY", act.getId(),
                null, JsonUtil.toJson(cmd));
        log.info("stage=ACTIVITY_CREATE activityId={} goodsCount={}", act.getId(), cmd.getGoods().size());
        return act.getId();
    }

    /**
     * 发布校验。
     *
     * <p>这些校验必须在发布时做完，不能留到活动开始时——活动开始是无人值守的定时推进，
     * 那时发现配置有问题已经来不及了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(long activityId, long adminId) {
        SeckillActivity act = activityMapper.selectById(activityId);
        Assert.requireFound(act, ErrorCode.ACTIVITY_NOT_FOUND);
        Assert.require(act.getStatus() == ActivityStatus.DRAFT.code(),
                ErrorCode.ACTIVITY_STATUS_ILLEGAL, "只有草稿状态的活动可以发布");

        Assert.require(act.getEndTime().isAfter(act.getStartTime()), "结束时间必须晚于开始时间");
        Assert.require(act.getStartTime().isAfter(LocalDateTime.now()), "开始时间必须晚于当前时间");

        List<SeckillGoods> goodsList = goodsMapper.selectByActivity(activityId);
        Assert.require(!goodsList.isEmpty(), "活动必须包含商品");

        for (SeckillGoods g : goodsList) {
            Assert.require(g.getSeckillPrice().signum() > 0, "秒杀价必须大于 0");
            Assert.require(g.getTotalStock() > 0, "秒杀库存必须大于 0");
            Assert.require(g.getLimitPerUser() == 1, "当前版本限购必须为 1");

            Sku sku = productService.getSku(g.getSkuId());
            Assert.require(sku != null && sku.getStatus() == 1,
                    ErrorCode.SKU_NOT_FOUND, "SKU 不存在或已下架: " + g.getSkuId());
            Assert.require(g.getTotalStock() <= sku.getStock(),
                    "秒杀库存不能超过 SKU 可用库存: skuId=" + g.getSkuId());
            Assert.require(g.getSeckillPrice().compareTo(sku.getPrice()) <= 0,
                    "秒杀价不应高于日常价: skuId=" + g.getSkuId());

            Assert.require(activityMapper.countOverlapping(g.getSkuId(),
                            act.getStartTime(), act.getEndTime(), activityId) == 0,
                    "该 SKU 在此时间段已参加其他活动: skuId=" + g.getSkuId());
        }

        int rows = activityMapper.updateStatus(activityId,
                ActivityStatus.DRAFT.code(), ActivityStatus.READY.code());
        if (rows == 0) {
            throw new BizException(ErrorCode.ACTIVITY_STATUS_ILLEGAL, "活动状态已变更，请刷新重试");
        }

        // 阶段一在这里直接 markWarmupDone 充当预热的替身（否则定时任务的
        // warmup_state = 2 条件不成立，活动永远进不了 RUNNING）。
        // 阶段二有了真正的预热任务，这个替身必须移除 —— 留着它会让"未预热的活动
        // 不进入 RUNNING"（F13）这条保护彻底失效：发布即视为预热完成，
        // 于是活动到点开抢，而 Redis 里什么都没有，全部请求返回 2005。

        evictDetailAfterCommit(activityId);
        audit.record(adminId, "ACTIVITY_PUBLISH", "ACTIVITY", activityId,
                String.valueOf(ActivityStatus.DRAFT.code()),
                String.valueOf(ActivityStatus.READY.code()));
        log.info("stage=ACTIVITY_PUBLISH activityId={} goodsCount={}", activityId, goodsList.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(long activityId, long adminId) {
        SeckillActivity act = activityMapper.selectById(activityId);
        Assert.requireFound(act, ErrorCode.ACTIVITY_NOT_FOUND);
        if (act.getStatus() == ActivityStatus.CLOSED.code()) {
            return;                       // 幂等
        }
        activityMapper.updateStatus(activityId, act.getStatus(), ActivityStatus.CLOSED.code());
        goodsMapper.updateStatusByActivity(activityId, SeckillGoods.STATUS_OFF_SALE);

        // 关闭活动必须同时停掉 Redis 侧的资格分配，否则 Lua 仍按时间窗口放行，
        // 用户继续抢到资格、继续建单——管理员点了"关闭"却毫无效果。
        // 这里改的是预热写进去的 status 字段，Lua 第二步就会以 -2 拒绝
        offSaleInRedis(activityId);
        evictDetailAfterCommit(activityId);

        audit.record(adminId, "ACTIVITY_CLOSE", "ACTIVITY", activityId,
                String.valueOf(act.getStatus()), String.valueOf(ActivityStatus.CLOSED.code()));
        log.warn("stage=ACTIVITY_CLOSE activityId={} from={}", activityId, act.getStatus());
    }

    /**
     * 库存调整。
     *
     * <p>{@code total_stock} 与 {@code available_stock} <b>必须同步调整</b>，
     * 否则库存对账等式 {@code total = available + locked + sold} 立刻不成立，
     * 对账任务会马上报差异。
     *
     * <p>Redis 侧的库存也要跟着改，否则活动进行中加了库存却没人抢得到
     * （Lua 读的是 Redis）。<b>但绝不能用 SET 把 Redis 库存写成 DB 的值</b>——
     * 那等于把已扣减的部分全部还回去，是超卖。只能用 {@code INCRBY delta}
     * 做相对调整，让"已经扣掉多少"这个信息留在 Redis 自己手里。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustStock(long goodsId, int delta, String reason, long adminId) {
        Assert.require(delta != 0, "调整量不能为 0");

        // 管理操作低频，可以用行锁把"读当前值"和"改"串起来，拿到准确的 before/after
        SeckillGoods g = goodsMapper.selectByIdForUpdate(goodsId);
        Assert.requireFound(g, ErrorCode.GOODS_NOT_FOUND);
        Assert.require(g.getAvailableStock() + delta >= 0, "调整后可售库存不能为负");

        int rows = goodsMapper.adjustStock(goodsId, delta);
        Assert.require(rows == 1, "库存调整失败，请重试");

        stockLogMapper.insert(StockLog.of(
                "ADJ-" + goodsId + "-" + System.nanoTime(), StockChangeType.MANUAL_ADJUST,
                delta, g.getActivityId(), g.getSkuId(),
                g.getAvailableStock(), g.getAvailableStock() + delta,
                "admin:" + adminId, reason));

        adjustRedisStockAfterCommit(g.getActivityId(), g.getSkuId(), delta);
        evictDetailAfterCommit(g.getActivityId());

        audit.record(adminId, "STOCK_ADJUST", "SECKILL_GOODS", goodsId,
                String.valueOf(g.getAvailableStock()),
                String.valueOf(g.getAvailableStock() + delta));
        log.warn("stage=STOCK_ADJUST goodsId={} delta={} reason={} admin={}",
                goodsId, delta, reason, adminId);
    }

    @Override
    public PageR<ActivityListItemVO> list(Integer status, long page, long size) {
        LambdaQueryWrapper<SeckillActivity> w = new LambdaQueryWrapper<SeckillActivity>()
                .ne(SeckillActivity::getStatus, ActivityStatus.DRAFT.code())
                .orderByDesc(SeckillActivity::getStartTime);
        if (status != null) {
            w.eq(SeckillActivity::getStatus, status);
        }
        Page<SeckillActivity> p = activityMapper.selectPage(Page.of(page, size), w);
        if (p.getRecords().isEmpty()) {
            return PageR.empty(page, size);
        }

        // 一次查出全部活动的商品数，避免 N+1
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (SeckillActivity a : p.getRecords()) {
            counts.put(a.getId(), goodsMapper.selectByActivity(a.getId()).size());
        }

        List<ActivityListItemVO> list = p.getRecords().stream()
                .map(a -> ActivityListItemVO.builder()
                        .activityId(a.getId())
                        .name(a.getName())
                        .startTime(a.getStartTime())
                        .endTime(a.getEndTime())
                        .status(a.getStatus())
                        .statusDesc(ActivityStatus.of(a.getStatus()).getDesc())
                        .goodsCount(counts.getOrDefault(a.getId(), 0))
                        .build())
                .toList();
        return PageR.of(p.getTotal(), page, size, list);
    }

    @Override
    public ActivityDetailVO detail(long activityId) {
        // 参数校验是防穿透的第一道：ID 非正数的请求连缓存都不必查
        Assert.require(activityId > 0, ErrorCode.ACTIVITY_NOT_FOUND);
        // Level 4：只保留订单查询与支付。商品浏览是优先级最低的那一档
        if (!degradeSwitch.browseEnabled()) {
            throw new BizException(ErrorCode.SERVICE_DEGRADED);
        }

        ActivityDetailVO cached = cache.get(RedisKeys.activityDetail(activityId),
                ActivityDetailVO.class, () -> loadDetailFromDb(activityId));
        // loader 返回 null 时缓存里落的是空值标记，这里统一转成业务异常
        Assert.requireFound(cached, ErrorCode.ACTIVITY_NOT_FOUND);

        return withVolatileFields(cached);
    }

    /**
     * 回源：从数据库拼出活动详情的静态骨架。
     *
     * <p>返回 {@code null} 而不是抛异常 —— 这样上层能把"不存在"这个结论也缓存住
     * （空值缓存，60s）。抛异常的话每个不存在的 ID 都会打到数据库，
     * 攻击者随手写个循环就能把 DB 打满。
     */
    private ActivityDetailVO loadDetailFromDb(long activityId) {
        SeckillActivity act = activityMapper.selectById(activityId);
        if (act == null || act.getStatus() == ActivityStatus.DRAFT.code()) {
            return null;                      // 草稿对外等同不存在
        }

        List<SeckillGoods> goodsList = goodsMapper.selectByActivity(activityId);
        List<Long> skuIds = goodsList.stream().map(SeckillGoods::getSkuId).toList();
        Map<Long, ProductService.SkuSnapshot> snapshots = skuIds.stream()
                .collect(Collectors.toMap(Function.identity(), productService::getSnapshot));

        List<ActivityDetailVO.GoodsItemVO> items = new ArrayList<>(goodsList.size());
        for (SeckillGoods g : goodsList) {
            ProductService.SkuSnapshot s = snapshots.get(g.getSkuId());
            Sku sku = s.sku();
            Product p = s.product();
            items.add(ActivityDetailVO.GoodsItemVO.builder()
                    .skuId(g.getSkuId())
                    .productTitle(p.getTitle())
                    .spec(sku.getSpec())
                    .originPrice(sku.getPrice())
                    .seckillPrice(g.getSeckillPrice())
                    .totalStock(g.getTotalStock())
                    .remainStock(g.getAvailableStock())
                    .limitPerUser(g.getLimitPerUser())
                    .soldOut(g.getAvailableStock() <= 0
                            || g.getStatus() == SeckillGoods.STATUS_SOLD_OUT)
                    .image(p.getMainImage())
                    .build());
        }

        return ActivityDetailVO.builder()
                .activityId(act.getId())
                .name(act.getName())
                .startTime(act.getStartTime())
                .endTime(act.getEndTime())
                .status(act.getStatus())
                .statusDesc(ActivityStatus.of(act.getStatus()).getDesc())
                .serverTime(LocalDateTime.now())
                .goodsList(items)
                .build();
    }

    /**
     * 覆盖不能缓存的字段。
     *
     * <p>缓存对象是每次从 JSON 反序列化出来的新实例，直接改它是安全的，
     * 不会污染其他请求。
     *
     * <ul>
     *   <li>{@code serverTime} 必须是真实当前时间，客户端靠它校准倒计时。
     *       缓存里那个是写入时刻，用它会让所有客户端的倒计时停在 2 小时前。</li>
     *   <li>{@code status} 由时间窗口<b>推导</b>而不是读 DB：READY → RUNNING → ENDED
     *       这三级迁移完全由时间决定，推导出来的结论和定时任务写进 DB 的一样，
     *       却不需要一次数据库往返。管理员显式关闭（CLOSED）无法从时间推导，
     *       所以关闭操作会主动清缓存。</li>
     *   <li>{@code remainStock} 取 Redis 实时库存 —— DB 的 available_stock 在异步化
     *       之后会滞后于 Redis（阶段三起 Redis 扣了但订单还没落库）。
     *       Redis 没有该 key（未预热/已过期）时退回缓存里的 DB 值。</li>
     * </ul>
     */
    private ActivityDetailVO withVolatileFields(ActivityDetailVO vo) {
        LocalDateTime now = LocalDateTime.now();
        vo.setServerTime(now);

        if (vo.getStatus() != null && vo.getStatus() != ActivityStatus.CLOSED.code()) {
            ActivityStatus derived = now.isBefore(vo.getStartTime()) ? ActivityStatus.READY
                    : now.isBefore(vo.getEndTime()) ? ActivityStatus.RUNNING
                    : ActivityStatus.ENDED;
            vo.setStatus(derived.code());
            vo.setStatusDesc(derived.getDesc());
        }

        // Level 1 起不再展示精确库存。这一档降级省掉的正是下面这个 per-SKU 的
        // Redis 读——一个活动 10 个 SKU 就是 10 次往返，而详情接口是全站 QPS 最高的
        boolean exact = degradeSwitch.showExactStock();
        if (vo.getGoodsList() != null) {
            for (ActivityDetailVO.GoodsItemVO item : vo.getGoodsList()) {
                if (!exact) {
                    // 只保留"有货/无货"这一个比特。用缓存里的 DB 快照判断——
                    // 它可能滞后，但降级期间"大概还有货"这个精度足够
                    item.setStockLevel(item.getRemainStock() != null && item.getRemainStock() > 0
                            ? ActivityDetailVO.StockLevel.AVAILABLE
                            : ActivityDetailVO.StockLevel.SOLD_OUT);
                    item.setRemainStock(null);
                    continue;
                }
                Long redisStock = redisStock(vo.getActivityId(), item.getSkuId());
                if (redisStock != null) {
                    item.setRemainStock((int) Math.max(0, redisStock));
                }
                item.setSoldOut(item.getRemainStock() != null && item.getRemainStock() <= 0);
                item.setStockLevel(ActivityDetailVO.StockLevel.of(
                        item.getRemainStock(), item.getTotalStock()));
            }
        }
        return vo;
    }

    private Long redisStock(long activityId, long skuId) {
        try {
            String v = redis.opsForValue().get(RedisKeys.stock(activityId, skuId));
            return v == null ? null : Long.parseLong(v);
        } catch (Exception e) {
            // 库存展示读失败不该让详情接口挂掉，退回缓存里的 DB 值
            log.warn("读取 Redis 库存失败 activityId={} skuId={}", activityId, skuId, e);
            return null;
        }
    }

    /**
     * 缓存失效放在<b>提交之后</b>。
     *
     * <p>提交前删缓存的话，删除与提交之间进来的读请求会回源读到<b>旧</b>数据
     * （事务还没提交，它看不到新值）并把旧值重新写进缓存——缓存里于是留下一份
     * 永远不会自愈的脏数据，直到 TTL 到期。这个窗口只有几毫秒，
     * 但在活动开抢前的管理操作上，几毫秒里可能有上万次读。
     */
    private void evictDetailAfterCommit(long activityId) {
        TxSupport.afterCommit("evict:activity:" + activityId,
                () -> cache.evict(RedisKeys.activityDetail(activityId)));
    }

    /**
     * Redis 侧库存相对调整。
     *
     * <p>只能 {@code INCRBY delta}，不能按 DB 的值 SET —— 见 {@link #adjustStock} 注释。
     *
     * <p><b>活动进行中做负向调整是有风险的操作</b>：DB 的
     * {@code available_stock + delta >= 0} 校验管不住 Redis，异步化之后 Redis 库存
     * 会低于 DB（差值就是排队中的量），减多了会把 Redis 打成负数。
     * 这里做兜底钳位并按 P1 记日志；真要减库存，正确做法是关闭活动而不是负向调整。
     */
    private void adjustRedisStockAfterCommit(long activityId, long skuId, int delta) {
        TxSupport.afterCommit("adjustRedisStock:" + activityId + ":" + skuId, () -> {
            String stockKey = RedisKeys.stock(activityId, skuId);
            if (Boolean.FALSE.equals(redis.hasKey(stockKey))) {
                // 还没预热，改 DB 就够了：预热时会读到调整后的 available_stock
                log.info("stage=STOCK_ADJUST_REDIS activityId={} skuId={} result=SKIP 未预热",
                        activityId, skuId);
                return;
            }
            Long after = redis.opsForValue().increment(stockKey, delta);
            if (after != null && after < 0) {
                redis.opsForValue().increment(stockKey, -after);
                log.error("stage=STOCK_ADJUST_REDIS activityId={} skuId={} delta={} "
                                + "result=CLAMPED after={} severity=P1 Redis 库存被减成负数已钳回 0",
                        activityId, skuId, delta, after);
                after = 0L;
            }
            if (after != null && after > 0) {
                // 库存从 0 恢复，必须取消售罄快速失败标记，否则加了库存也没人抢得到
                String goodsKey = RedisKeys.goods(activityId, skuId);
                Object status = redis.opsForHash().get(goodsKey, "status");
                if (String.valueOf(SeckillGoods.STATUS_SOLD_OUT).equals(String.valueOf(status))) {
                    redis.opsForHash().put(goodsKey, "status",
                            String.valueOf(SeckillGoods.STATUS_ON_SALE));
                }
            }
            log.warn("stage=STOCK_ADJUST_REDIS activityId={} skuId={} delta={} after={}",
                    activityId, skuId, delta, after);
        });
    }

    /** 关闭活动时把 Redis 侧的商品状态改成停售，让 Lua 立即拒绝新请求 */
    private void offSaleInRedis(long activityId) {
        TxSupport.afterCommit("offSale:" + activityId, () -> {
            for (Long skuId : goodsMapper.selectSkuIds(activityId)) {
                String goodsKey = RedisKeys.goods(activityId, skuId);
                if (Boolean.TRUE.equals(redis.hasKey(goodsKey))) {
                    redis.opsForHash().put(goodsKey, "status",
                            String.valueOf(SeckillGoods.STATUS_OFF_SALE));
                }
            }
        });
    }

    @Override
    public SeckillGoods getGoods(long activityId, long skuId) {
        return goodsMapper.selectByActivitySku(activityId, skuId);
    }
}
