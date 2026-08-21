package com.fss.biz.seckill.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fss.biz.mq.ReliableMqProducer;
import com.fss.biz.seckill.core.SeckillCompensateService;
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
import com.fss.infra.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 秒杀服务 —— 阶段三：Redis Lua 原子判扣 + <b>MQ 异步落库</b>。
 *
 * <p>三个阶段的演进都发生在这一个方法里，落库逻辑
 * （{@code OrderCreateService.handle}）始终没动过：
 * <ol>
 *   <li>阶段一：直接同步落库，靠 MySQL 条件更新防超卖</li>
 *   <li>阶段二：Lua 判扣通过后再同步落库。资格分配的权威从 MySQL 挪到 Redis，
 *       MySQL 的条件更新退化为兜底</li>
 *   <li>阶段三：Lua 通过后<b>只投一条消息就返回</b>，落库由消费端做</li>
 * </ol>
 *
 * <h3>异步化换来什么，代价是什么</h3>
 * 换来的是接口 RT 与 MySQL 写入速度解耦：秒杀接口的耗时变成"一段 Lua + 一次
 * 单行 INSERT + 一次 send"，不再包含建订单那 4 张表的写入与热点行竞争。
 * 10000 并发下 MySQL 只需要按自己的节奏消费 1000 条消息。
 *
 * <p>代价是<b>用户拿不到即时结论</b>：接口返回"排队中"，客户端得轮询。
 * 这不只是体验问题，它引入了一整类新的失败模式——消息丢了、消费端挂了、
 * 处理失败了，用户会永远停在"排队中"。所以阶段三真正的工作量不在这个方法，
 * 而在本地消息表、幂等三层、死信处理、重发任务这些"让排队中一定会有结论"的机制上。
 *
 * <p><b>为什么预扣成功后投递失败必须回补</b>：Redis 已经扣了库存、记了用户标记，
 * 而没有任何人会为他建订单。不回补这一份库存就永久躺在已扣减里，
 * 1000 库存的活动最后只卖出 997 件，而且账面上"已售罄"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SeckillExecutor          executor;
    private final SeckillTokenService      tokenService;
    private final SeckillCompensateService compensateService;
    private final ReliableMqProducer       producer;
    private final OrderMapper              orderMapper;
    private final SeckillRequestMapper     requestMapper;
    private final FssProperties            props;

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
            // 这里补一条结论进去：客户端可能已经在轮询，给它明确答案比让它等超时好。
            //
            // 注意这不是"回补"——这些请求根本没扣过库存，只需要写结论
            executor.writeResult(activityId, skuId, requestNo, userId,
                    rejectStatusOf(ec), null, ec.getMessage());
            recordRejection(msg, ec);
            throw new BizException(ec);
        }

        // 4. 投递创建订单消息。先落本地消息表，再 send；send 失败由重发任务兜住
        try {
            producer.sendReliable(MqTopics.ORDER_CREATE, requestNo, msg, null);
        } catch (Exception e) {
            // 走到这里说明<b>连本地消息表都写不进去</b>（send 失败不会抛到这里，
            // ReliableMqProducer 内部吞掉并转交重发任务）。
            // 投递意图彻底没能落盘，没有任何后续机制会为这个用户建订单 →
            // 必须就地回补，否则这一份库存永久泄漏
            log.error("stage=SECKILL_SUBMIT requestNo={} result=MQ_PERSIST_FAILED 立即回补",
                    requestNo, e);
            compensateService.rollback(msg, ErrorCode.SYSTEM_BUSY, "消息登记失败，已退回");
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        }

        log.info("stage=SECKILL_SUBMIT requestNo={} userId={} activityId={} skuId={} "
                        + "result=QUEUEING remain={}",
                requestNo, userId, activityId, skuId, outcome.remainStock());
        // 剩余库存报 Redis 的值而不是 DB 的：Redis 才是资格分配的权威，
        // 而且异步化之后 DB 的 available_stock 会滞后于真实可抢量，
        // 两者的差值正是"排队中"的量
        return SeckillSubmitVO.queueing(requestNo, (int) outcome.remainStock(),
                props.getDegrade().getPollIntervalMs());
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

    /** Lua 直接拒绝时的请求终态。这些请求没扣过库存，不需要回补，只需要一个结论 */
    private SeckillRequestStatus rejectStatusOf(ErrorCode ec) {
        return switch (ec) {
            case STOCK_NOT_ENOUGH -> SeckillRequestStatus.STOCK_NOT_ENOUGH;
            case ALREADY_BOUGHT   -> SeckillRequestStatus.ALREADY_BOUGHT;
            default               -> SeckillRequestStatus.CREATE_FAILED;
        };
    }

    /**
     * Lua 拒绝的请求落一条 {@code t_seckill_request}，供用户查询与对账。
     *
     * <p>写不进去不影响给用户的结论（异常是同步抛给他的），所以异常只记日志——
     * 不能因为审计记录写失败就把"库存不足"换成"系统错误"。
     */
    private void recordRejection(OrderCreateMessage msg, ErrorCode ec) {
        try {
            requestMapper.insert(SeckillRequest.builder()
                    .requestNo(msg.getRequestNo())
                    .userId(msg.getUserId())
                    .activityId(msg.getActivityId())
                    .skuId(msg.getSkuId())
                    .quantity(msg.getQuantity())
                    .status(rejectStatusOf(ec).code())
                    .failReason(ec.getMessage())
                    .traceId(msg.getTraceId())
                    .build());
        } catch (Exception ignored) {
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
