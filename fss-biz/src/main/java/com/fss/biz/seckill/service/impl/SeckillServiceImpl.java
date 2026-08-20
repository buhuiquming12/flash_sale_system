package com.fss.biz.seckill.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fss.biz.order.service.OrderCreateService;
import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.biz.seckill.core.SeckillOutcome;
import com.fss.biz.seckill.core.SeckillTokenService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillResultVO;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.Assert;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.IdGenerator;
import com.fss.domain.entity.Order;
import com.fss.domain.entity.SeckillRequest;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillRequestMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.config.FssProperties;
import com.fss.infra.config.SentinelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 秒杀服务 —— 阶段二：Redis Lua 原子判扣 + <b>同步</b>落库。
 *
 * <p>三个阶段的演进都发生在这一个方法里，落库逻辑
 * （{@link OrderCreateService#handle}）始终没动过：
 * <ol>
 *   <li>阶段一：直接同步落库，靠 MySQL 条件更新防超卖</li>
 *   <li>阶段二：<b>Lua 判扣通过后</b>再同步落库。此时资格分配的权威从 MySQL
 *       挪到了 Redis，MySQL 的条件更新退化为兜底</li>
 *   <li>阶段三：Lua 通过后投递 MQ，由消费端调同一个 handle 落库</li>
 * </ol>
 * 阶段二仍然同步落库是有意的：单独验证 Lua 的正确性，不被 MQ 的异步性干扰。
 * 如果这一步就有超卖，加了 MQ 只会让它更难复现。
 *
 * <p><b>为什么 Lua 通过后落库失败必须回补</b>：Redis 已经扣了库存、记了用户标记，
 * 而订单没建出来。不回补的话这一份库存就永久躺在 Redis 的已扣减里，
 * 没有任何人拿到商品——1000 库存的活动最后只卖出 997 件，
 * 而且账面上"已售罄"。同步链路下回补可以就地做完；
 * 阶段三消费端落库失败时，回补会变成一条 STOCK_ROLLBACK 消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final OrderCreateService   orderCreateService;
    private final SeckillExecutor      executor;
    private final SeckillTokenService  tokenService;
    private final OrderMapper          orderMapper;
    private final SeckillRequestMapper requestMapper;
    private final FssProperties        props;

    /**
     * {@code exceptionsToIgnore = BizException.class} 是这段集成里最关键的一行。
     *
     * <p>不写它的话："库存不足"会被 Sentinel 计入异常比例，几秒钟就把熔断器打开，
     * 于是活动一售罄整个接口就熔断了；同时 {@code fallback} 会接住这个异常，
     * 把"库存不足"改写成"系统繁忙"——用户以为是系统问题，于是不停重试，
     * 反而放大流量。业务失败不是故障，不能进故障统计。
     */
    @Override
    @SentinelResource(value = SentinelConfig.RES_SECKILL_SUBMIT,
            blockHandler = "onSubmitBlocked",
            fallback = "onSubmitFallback",
            exceptionsToIgnore = BizException.class)
    public SeckillSubmitVO submit(SeckillCmd cmd, long userId) {
        // 1. 降级开关。放在最前面：降级的目的就是不消耗后面的资源
        if (!props.getDegrade().isSeckillEnabled()) {
            throw new BizException(ErrorCode.SERVICE_DEGRADED);
        }
        Assert.require(cmd.getActivityId() != null && cmd.getActivityId() > 0, "活动 ID 不合法");
        Assert.require(cmd.getSkuId() != null && cmd.getSkuId() > 0, "SKU ID 不合法");

        int quantity = cmd.getQuantity() == null ? 1 : cmd.getQuantity();
        Assert.require(quantity == 1, ErrorCode.PARAM_INVALID, "当前版本仅支持单件购买");

        long activityId = cmd.getActivityId();
        long skuId      = cmd.getSkuId();

        // 2. 令牌校验（若携带）。GETDEL 一次性消费，见 SeckillTokenService
        if (cmd.getToken() != null && !cmd.getToken().isBlank()) {
            tokenService.verifyAndConsume(cmd.getToken(), userId, activityId, skuId);
        }

        String requestNo = IdGenerator.requestNo();
        String traceId   = TraceContext.get();
        OrderCreateMessage msg = OrderCreateMessage.builder()
                .requestNo(requestNo)
                .userId(userId)
                .activityId(activityId)
                .skuId(skuId)
                .quantity(quantity)
                .requestTime(LocalDateTime.now())
                .traceId(traceId)
                .version(OrderCreateMessage.CURRENT_VERSION)
                .build();

        // 3. Lua 原子判扣：活动校验、时间校验（Redis 时钟）、一人一单、库存预扣、
        //    写请求记录，五件事一次完成。这是整个系统唯一的资格分配入口
        SeckillOutcome outcome = executor.trySeckill(
                activityId, skuId, userId, quantity, requestNo, traceId);
        if (!outcome.qualified()) {
            ErrorCode ec = outcome.errorCode();
            // Lua 在时间/库存校验阶段直接返回，没有创建 req key，
            // 这里补一条结论进去：客户端可能已经在轮询，给它明确答案比让它等超时好
            executor.writeResult(activityId, skuId, requestNo, userId,
                    statusOf(ec), null, ec.getMessage());
            recordFailure(msg, ec, ec.getMessage());
            throw new BizException(ec);
        }

        // 4. 同步落库。失败必须回补 Redis，否则库存永久泄漏
        OrderCreateService.Created created;
        try {
            created = orderCreateService.handle(msg);
        } catch (BizException e) {
            rollbackAfterCreateFailed(msg, e.getErrorCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("stage=ORDER_CREATE requestNo={} result=ERROR 未预期异常", requestNo, e);
            rollbackAfterCreateFailed(msg, ErrorCode.SYSTEM_ERROR, "系统异常");
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        Order order = created.order();

        // 5. 回写成功结论。终态不会被后续重试覆盖（脚本 D 的条件写）
        executor.writeResult(activityId, skuId, requestNo, userId,
                SeckillRequestStatus.SUCCESS, order.getOrderNo(), null);

        log.info("stage=SECKILL_SUBMIT requestNo={} userId={} activityId={} skuId={} orderNo={} remain={}",
                requestNo, userId, activityId, skuId, order.getOrderNo(), outcome.remainStock());
        // 剩余库存报 Redis 的值而不是 DB 的：Redis 才是资格分配的权威，
        // 而且异步化之后 DB 的 available_stock 会滞后于真实可抢量
        return SeckillSubmitVO.success(requestNo, order.getOrderNo(),
                (int) outcome.remainStock(), order.getExpireTime());
    }

    /** Sentinel 限流/熔断触发。签名 = 原方法 + BlockException */
    public SeckillSubmitVO onSubmitBlocked(SeckillCmd cmd, long userId, BlockException e) {
        log.warn("stage=SECKILL_SUBMIT userId={} activityId={} result=BLOCKED by={}",
                userId, cmd.getActivityId(), e.getClass().getSimpleName());
        throw new BizException(ErrorCode.RATE_LIMITED, "活动太火爆，请稍后再试");
    }

    /**
     * Sentinel 降级兜底。
     *
     * <p>即使已经配了 {@code exceptionsToIgnore}，这里仍然显式把 BizException 原样抛出——
     * 双重保险的成本是三行代码，而漏掉它的后果是所有业务错误码被吞成"系统繁忙"，
     * 这是 Sentinel 集成最常见也最难发现的坑（接口不报错、监控不报警，只有用户在抱怨）。
     */
    public SeckillSubmitVO onSubmitFallback(SeckillCmd cmd, long userId, Throwable t) {
        if (t instanceof BizException be) {
            throw be;
        }
        log.error("stage=SECKILL_SUBMIT userId={} activityId={} result=FALLBACK",
                userId, cmd.getActivityId(), t);
        throw new BizException(ErrorCode.SYSTEM_BUSY);
    }

    @Override
    @SentinelResource(value = SentinelConfig.RES_SECKILL_RESULT,
            blockHandler = "onResultBlocked",
            exceptionsToIgnore = BizException.class)
    public SeckillResultVO queryResult(String requestNo, Long activityId, Long skuId, long userId) {
        Assert.require(requestNo != null && !requestNo.isBlank(), "请求号不能为空");

        // ---- 先查 Redis ----
        // 需要 activityId + skuId 才能定位 key：req key 带着 {activityId:skuId} hash tag，
        // 而这个 tag 是必需的（它要和 stock / bought 落在同一个 Cluster 槽，
        // 才能被同一段 Lua 原子操作）。客户端刚提交过秒杀，这两个值它一定有
        if (activityId != null && skuId != null) {
            SeckillExecutor.RequestResult r = executor.readResult(activityId, skuId, requestNo);
            if (r != null) {
                assertOwner(r.userId(), userId);
                SeckillRequestStatus s = SeckillRequestStatus.of(r.status());
                return SeckillResultVO.of(s, requestNo, r.orderNo(), r.reason());
            }
        }

        // ---- 回查数据库 ----
        // 这条路径不是冗余：Redis 结果 TTL 只有 30 分钟，而且回写可能失败。
        // 数据库才是结论的权威来源
        Order order = orderMapper.selectByRequestNo(requestNo);
        if (order != null) {
            assertOwner(order.getUserId(), userId);
            return SeckillResultVO.of(SeckillRequestStatus.SUCCESS, requestNo,
                    order.getOrderNo(), null);
        }
        SeckillRequest req = requestMapper.selectByRequestNo(requestNo);
        if (req != null) {
            assertOwner(req.getUserId(), userId);
            return SeckillResultVO.of(SeckillRequestStatus.of(req.getStatus()),
                    requestNo, req.getOrderNo(), req.getFailReason());
        }
        throw new BizException(ErrorCode.REQUEST_NOT_FOUND);
    }

    public SeckillResultVO onResultBlocked(String requestNo, Long activityId, Long skuId,
                                           long userId, BlockException e) {
        throw new BizException(ErrorCode.RATE_LIMITED, "查询过于频繁，请稍后再试");
    }

    // ------------------------------------------------------------------

    /**
     * 落库失败后的补偿回补。
     *
     * <p>{@code keepBought} 的取值是这里唯一需要想清楚的地方：
     * 失败原因是 {@code ALREADY_BOUGHT} 时<b>绝不能</b>归还购买资格。
     * 那种情况说明 DB 里已经有这个用户的订单（哪怕是已取消的，唯一键仍然占着），
     * 归还资格 → 用户重抢 → Redis 放行 → DB 又冲突 → 又回补，无限循环。
     * 其余失败（系统异常、商品不存在）是用户没有责任的，资格要还给他。
     */
    private void rollbackAfterCreateFailed(OrderCreateMessage msg, ErrorCode ec, String reason) {
        boolean keepBought = ec == ErrorCode.ALREADY_BOUGHT;
        // reason 传<b>面向用户的文案</b>而不是错误码名。脚本 B 会把请求状态置成
        // COMPENSATED（"系统繁忙已退回"），而真实原因可能是"您已参与过本次秒杀" ——
        // 客户端展示的是 failReason，拿不到具体原因就只能给出一句放之四海皆准的
        // "系统繁忙"，用户于是不停重试
        executor.rollback(msg.getActivityId(), msg.getSkuId(), msg.getUserId(),
                msg.getQuantity(), msg.getRequestNo(), ec.getMessage(), keepBought);
        recordFailure(msg, ec, reason);
    }

    private SeckillRequestStatus statusOf(ErrorCode ec) {
        return switch (ec) {
            case STOCK_NOT_ENOUGH -> SeckillRequestStatus.STOCK_NOT_ENOUGH;
            case ALREADY_BOUGHT   -> SeckillRequestStatus.ALREADY_BOUGHT;
            default               -> SeckillRequestStatus.CREATE_FAILED;
        };
    }

    /**
     * 失败结论落 {@code t_seckill_request}，供用户查询与对账。
     *
     * <p>必须在<b>已回滚的事务之外</b>执行：{@code handle} 的事务因异常回滚了，
     * 在同一事务里写失败记录会被一起回滚，用户就查不到失败原因。
     */
    private void recordFailure(OrderCreateMessage msg, ErrorCode ec, String reason) {
        try {
            requestMapper.insert(SeckillRequest.builder()
                    .requestNo(msg.getRequestNo())
                    .userId(msg.getUserId())
                    .activityId(msg.getActivityId())
                    .skuId(msg.getSkuId())
                    .quantity(msg.getQuantity())
                    .status(statusOf(ec).code())
                    .failReason(reason)
                    .traceId(msg.getTraceId())
                    .build());
        } catch (Exception ignored) {
            // 失败记录写不进去不影响给用户的结论，不能因此把业务错误码换成系统错误
            log.warn("秒杀失败记录写入失败 requestNo={}", msg.getRequestNo());
        }
    }

    private void assertOwner(Object ownerId, long userId) {
        if (ownerId == null || !String.valueOf(ownerId).equals(String.valueOf(userId))) {
            // 与"不存在"返回同一个码，不泄漏请求号的有效性
            throw new BizException(ErrorCode.REQUEST_NOT_FOUND);
        }
    }
}
