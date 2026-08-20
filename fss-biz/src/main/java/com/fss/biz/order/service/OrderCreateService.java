package com.fss.biz.order.service;

import com.fss.biz.audit.AdminAuditService;
import com.fss.biz.product.service.ProductService;
import com.fss.common.enums.ActivityStatus;
import com.fss.common.enums.OrderStatus;
import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.enums.StockChangeType;
import com.fss.common.error.Assert;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.util.IdGenerator;
import com.fss.domain.entity.Order;
import com.fss.domain.entity.OrderItem;
import com.fss.domain.entity.SeckillActivity;
import com.fss.domain.entity.SeckillGoods;
import com.fss.domain.entity.SeckillRequest;
import com.fss.domain.entity.StockLog;
import com.fss.domain.mapper.OrderItemMapper;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.domain.mapper.SeckillRequestMapper;
import com.fss.domain.mapper.StockLogMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.config.FssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单落库 —— 全系统最需要写对的一段事务。
 *
 * <p>阶段一由秒杀接口同步调用；阶段三由 MQ 消费端调用，<b>方法体不变</b>。
 * 这是把异步化的风险限制在"谁来调用"而不是"怎么落库"的关键。
 *
 * <p>幂等三层：
 * <ol>
 *   <li>L1 前置查询 {@code request_no}：拦掉绝大多数重复投递。存在的意义是
 *       避免重复消息走到 insert 才失败——{@code DuplicateKeyException} 会让事务
 *       回滚，产生无用的 undo log 和错误日志噪音。</li>
 *   <li>L2 唯一约束 {@code uk_request_no} / {@code uk_activity_sku_user}：
 *       L1 漏掉的并发情况由数据库层串行化兜住。<b>这是唯一不可省的一层。</b></li>
 *   <li>L3 状态条件更新：用于关单、支付这类没有新行插入的状态变更。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final OrderMapper           orderMapper;
    private final OrderItemMapper       orderItemMapper;
    private final SeckillGoodsMapper    goodsMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillRequestMapper  requestMapper;
    private final StockLogMapper        stockLogMapper;
    private final ProductService        productService;
    private final FssProperties         props;

    /**
     * @return 落库结果；若为重复请求（L1 命中）则 {@code duplicate = true}
     *         且携带已存在的那张订单
     */
    @Transactional(rollbackFor = Exception.class)
    public Created handle(OrderCreateMessage msg) {
        long t0 = System.currentTimeMillis();

        // ---- 幂等层 1：请求号已处理？ ----
        Order exist = orderMapper.selectByRequestNo(msg.getRequestNo());
        if (exist != null) {
            log.info("stage=ORDER_CREATE requestNo={} result=DUPLICATE orderNo={}",
                    msg.getRequestNo(), exist.getOrderNo());
            return new Created(exist, -1, true);   // 不做任何写操作，调用方可直接 ACK
        }

        // ---- 幂等层 2 的前置查询：该用户在该活动该 SKU 上已有订单？ ----
        // 注意不带 status 条件：已取消的订单同样占用 uk_activity_sku_user（决策 1）
        Order dup = orderMapper.selectByActivitySkuUser(
                msg.getActivityId(), msg.getSkuId(), msg.getUserId());
        if (dup != null) {
            // 确定性失败：重试永远不会成功，调用方须走补偿回补而非 MQ 重试
            throw new BizException(ErrorCode.ALREADY_BOUGHT);
        }

        SeckillGoods goods = goodsMapper.selectByActivitySku(msg.getActivityId(), msg.getSkuId());
        Assert.requireFound(goods, ErrorCode.GOODS_NOT_FOUND);
        assertSellable(goods, msg.getQuantity());

        // ---- 条件扣减：一条语句完成判断 + 扣减，影响行数 0 就是库存不足 ----
        int rows = goodsMapper.deductStock(goods.getId(), msg.getQuantity());
        if (rows == 0) {
            log.info("stage=STOCK_DEDUCT_DB goodsId={} qty={} rows=0 result=NOT_ENOUGH",
                    goods.getId(), msg.getQuantity());
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
        log.info("stage=STOCK_DEDUCT_DB goodsId={} qty={} rows={}",
                goods.getId(), msg.getQuantity(), rows);

        // ---- 建订单。价格来自 DB，绝不采信消息体 ----
        BigDecimal unitPrice = goods.getSeckillPrice();
        BigDecimal payAmount = unitPrice.multiply(BigDecimal.valueOf(msg.getQuantity()));
        ProductService.SkuSnapshot snap = productService.getSnapshot(msg.getSkuId());
        BigDecimal originAmount = snap.sku().getPrice()
                .multiply(BigDecimal.valueOf(msg.getQuantity()));
        LocalDateTime expireTime = LocalDateTime.now().plus(props.getOrder().getPayTimeout());

        Order order = Order.builder()
                .orderNo(IdGenerator.orderNo())
                .requestNo(msg.getRequestNo())
                .userId(msg.getUserId())
                .activityId(msg.getActivityId())
                .skuId(msg.getSkuId())
                .status(OrderStatus.PENDING_PAY.code())
                .totalAmount(originAmount)
                .payAmount(payAmount)
                .quantity(msg.getQuantity())
                .stockReleased(0)
                .expireTime(expireTime)
                .build();
        insertOrder(order);

        // ---- 明细快照。商品改名改价下架都不影响历史订单展示 ----
        orderItemMapper.insert(OrderItem.builder()
                .orderNo(order.getOrderNo())
                .skuId(snap.sku().getId())
                .productId(snap.product().getId())
                .productTitle(snap.product().getTitle())
                .specSnapshot(snap.sku().getSpec())
                .imageSnapshot(snap.product().getMainImage())
                .unitPrice(unitPrice)
                .quantity(msg.getQuantity())
                .build());

        // ---- 库存流水，uk_biz_type 保证同一请求的确认扣减只记一条 ----
        stockLogMapper.insert(StockLog.of(msg.getRequestNo(), StockChangeType.CONFIRM_DEDUCT,
                -msg.getQuantity(), msg.getActivityId(), msg.getSkuId(),
                goods.getAvailableStock(), goods.getAvailableStock() - msg.getQuantity(),
                "user:" + msg.getUserId(), "秒杀下单"));

        // ---- 请求记录落库，供对账与长期审计 ----
        upsertRequest(msg, order.getOrderNo());

        log.info("stage=ORDER_CREATE requestNo={} orderNo={} userId={} amount={} result=OK cost={}ms",
                msg.getRequestNo(), order.getOrderNo(), msg.getUserId(), payAmount,
                System.currentTimeMillis() - t0);
        return new Created(order, goods.getAvailableStock() - msg.getQuantity(), false);
    }

    /**
     * @param remainStock 扣减后的可售库存。取自扣减前读到的值减去本次数量，
     *                    不再查一次库——那会在热点行上多一次读，且并发下读到的值
     *                    也只是别人扣减过程中的瞬时值，对展示没有额外价值。
     *                    L1 幂等命中时为 -1（未知）。
     */
    public record Created(Order order, int remainStock, boolean duplicate) {
    }

    /**
     * 活动与商品可售性校验 —— 兜底层，<b>不包含时间窗口判定</b>。
     *
     * <p>阶段一时间窗口判定在这里，用应用侧时钟；阶段二把它挪进了 Lua，用 Redis
     * {@code TIME}。<b>这里必须真的删掉而不是留着当"双重保险"</b>：
     * <ul>
     *   <li>多实例时钟漂移（故障用例 F14）：一台 web 实例时钟快 5 分钟，
     *       Lua 用 Redis 时钟判定为"未开始"正确拒绝，而这里用本机时钟判定为"进行中"。
     *       两个时钟只要有一个是错的，留着的那个就是错的那个。</li>
     *   <li>活动结束那一瞬间：Lua 判定通过、请求进入落库，此处再用本机时钟判一次
     *       就可能判成"已结束"，于是 Redis 扣了库存、订单没建成，
     *       白白触发一次补偿回补。判定点越多，边界上的不一致就越多。</li>
     * </ul>
     * 时间判定<b>只有一个权威时钟</b>，这是这条改动的全部意义。
     *
     * <p>保留下来的是与时间无关的校验：数量、限购、商品停售、活动被管理员关闭。
     * 这些在 Redis 里也有对应标记（预热写入 status、关闭活动时改 status），
     * 留在这里是防"Redis 数据被误删后 key 重建但标记丢失"，
     * 而它们的判定不依赖时钟，不会出现两个数据源互相矛盾的情况。
     */
    private void assertSellable(SeckillGoods goods, Integer quantity) {
        Assert.require(quantity != null && quantity > 0, "购买数量必须为正整数");
        Assert.require(quantity <= goods.getLimitPerUser(),
                ErrorCode.PARAM_INVALID, "超出每人限购数量 " + goods.getLimitPerUser());

        if (goods.getStatus() == SeckillGoods.STATUS_OFF_SALE) {
            throw new BizException(ErrorCode.GOODS_OFF_SALE);
        }

        SeckillActivity act = activityMapper.selectById(goods.getActivityId());
        Assert.requireFound(act, ErrorCode.ACTIVITY_NOT_FOUND);
        if (act.getStatus() == ActivityStatus.CLOSED.code()) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED, "活动已关闭");
        }
    }

    /**
     * insert 并区分唯一键冲突。
     *
     * <p><b>必须区分是哪个唯一键冲突，处理方式完全不同。</b>
     * 把两者统一当成"重复消息 → ACK"会让 {@code uk_activity_sku_user} 冲突的请求
     * 既没订单也不回补库存，库存永久泄漏。
     *
     * <p>靠字符串匹配索引名不优雅，但这里只是并发兜底——正常路径由上面的两次查询
     * 分流，走到这个 catch 说明是真正的并发撞车。
     */
    private void insertOrder(Order order) {
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("uk_request_no")) {
                // 同一 requestNo 被并发处理，另一方已建单。
                //
                // 这里不再查一次把那张订单捞回来：当前事务在 REPEATABLE READ 下的快照
                // 早于对方提交，查也查不到。抛 REQUEST_DUPLICATED 让调用方在新事务里
                // 重走 L1 查询即可拿到结论（消费端则直接 ACK）。
                throw new BizException(ErrorCode.REQUEST_DUPLICATED);
            }
            if (m.contains("uk_activity_sku_user")) {
                // 该用户已有订单，本次是不同 requestNo → 确定性失败，需补偿回补。
                // 抛出会回滚整个事务，本次的库存扣减自然撤销（同步链路下无需额外回补）
                throw new BizException(ErrorCode.ALREADY_BOUGHT);
            }
            if (m.contains("uk_order_no")) {
                throw new BizException(ErrorCode.SYSTEM_BUSY, "订单号冲突，请重试");
            }
            throw e;
        }
    }

    private void upsertRequest(OrderCreateMessage msg, String orderNo) {
        SeckillRequest exist = requestMapper.selectByRequestNo(msg.getRequestNo());
        if (exist == null) {
            requestMapper.insert(SeckillRequest.builder()
                    .requestNo(msg.getRequestNo())
                    .userId(msg.getUserId())
                    .activityId(msg.getActivityId())
                    .skuId(msg.getSkuId())
                    .quantity(msg.getQuantity())
                    .status(SeckillRequestStatus.SUCCESS.code())
                    .orderNo(orderNo)
                    .traceId(msg.getTraceId())
                    .build());
        } else {
            requestMapper.advanceStatus(msg.getRequestNo(),
                    SeckillRequestStatus.SUCCESS.code(), orderNo, null);
        }
    }
}
