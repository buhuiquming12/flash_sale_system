package com.fss.biz.job;

import com.fss.biz.order.service.OrderService;
import com.fss.domain.entity.Order;
import com.fss.domain.mapper.OrderMapper;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时订单关闭。
 *
 * <p>阶段一这是关单的<b>唯一</b>手段；阶段三接入 RocketMQ 定时消息后，
 * 它退化为兜底——处理定时消息丢失、消费失败的情况。
 *
 * <p>扫描时刻比订单过期时刻晚 {@code close-scan-delay}（默认 2 分钟），
 * 阶段三是为了避免和定时消息抢同一批订单造成大量无效竞争；即使抢到同一单，
 * 条件更新也保证只有一方成功。
 *
 * <p><b>多实例部署时这个方法必须加分布式锁。</b> 阶段二已补上
 * {@code @DistributedLock}（故障用例 F12：起 3 个 job 实例，每个任务只执行一次）。
 * 需要说清的是，锁在这里只省资源不保正确：关单走的是条件更新
 * {@code WHERE status = 0}，没有锁也不会重复关单或重复回补，只会浪费一次无效扫描。
 * 真正需要锁的理由是扫描本身要读几百行订单、逐张调 Service，
 * 三个实例同时干这件事等于把 DB 压力和连接占用乘以三。
 */
@Slf4j
@Component
@Profile("job")
@RequiredArgsConstructor
public class OrderCloseJob {

    private final OrderMapper   orderMapper;
    private final OrderService  orderService;
    private final FssProperties props;

    @Scheduled(cron = "${fss.job.close-expired-cron:0 */2 * * * ?}")
    @DistributedLock(key = "close-expired", leaseSeconds = 300)
    public void closeExpiredOrders() {
        LocalDateTime deadline = LocalDateTime.now().minus(props.getOrder().getCloseScanDelay());
        int batchSize = props.getOrder().getCloseScanBatch();
        long lastId = 0;
        int closed = 0;
        int failed = 0;

        while (true) {
            List<Order> batch = orderMapper.selectExpiredPendingPay(deadline, lastId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (Order o : batch) {
                try {
                    if (orderService.closeOrder(o.getOrderNo(), "超时未支付(扫描)")) {
                        closed++;
                    }
                } catch (Exception e) {
                    // 单张订单失败不能中断整批：否则一张脏数据会让后面所有订单永远关不掉
                    failed++;
                    log.error("stage=ORDER_CLOSE orderNo={} result=ERROR", o.getOrderNo(), e);
                }
            }
            lastId = batch.get(batch.size() - 1).getId();
        }

        if (closed > 0 || failed > 0) {
            log.info("stage=JOB_CLOSE_EXPIRED closed={} failed={} deadline={}",
                    closed, failed, deadline);
        }
    }
}
