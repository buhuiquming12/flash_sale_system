package com.fss.infra;

import com.fss.biz.order.model.OrderVO;
import com.fss.biz.order.service.OrderService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库与应用的时区一致性。
 *
 * <p><b>这是一个回归测试，对应一个真实踩过的坑。</b>
 * MySQL 容器默认时区是 UTC，{@code NOW(3)} 和列上的
 * {@code DEFAULT CURRENT_TIMESTAMP(3)} 会写入 UTC 时间；而应用按 Asia/Shanghai
 * 写入 {@code LocalDateTime}。两个时钟并存的后果：
 *
 * <ul>
 *   <li>{@code create_time}、{@code pay_time}、{@code cancel_time}（DB 侧生成）
 *       比 {@code expire_time}（Java 侧生成）慢 8 小时，接口返回的时间对不上</li>
 *   <li>更危险的是判定被污染：超时扫描的
 *       {@code WHERE expire_time < #{deadline}} 一边是 Java 时间一边是……
 *       只要有任何一处用 DB 时间和 Java 时间直接比较，结论就是错的</li>
 * </ul>
 *
 * <p>原先的用例抓不到它，因为断言的字段恰好都是 Java 侧写入的。
 */
class DatabaseTimezoneTest extends IntegrationTestBase {

    @Autowired SeckillService seckillService;
    @Autowired OrderService   orderService;
    @Autowired TestFixture    fixture;
    @Autowired JdbcTemplate   jdbc;

    @Test
    @DisplayName("MySQL NOW(3) 与应用本地时间偏差应在 1 分钟内")
    void 数据库时钟与应用时钟一致() {
        LocalDateTime dbNow = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
        long skewSeconds = Math.abs(Duration.between(dbNow, LocalDateTime.now()).getSeconds());

        assertThat(skewSeconds)
                .as("DB 时间 %s 与应用时间 %s 偏差 %ds。"
                        + "偏差接近 28800s(8h) 说明 MySQL 仍在 UTC，"
                        + "需要 --default-time-zone=+08:00", dbNow, LocalDateTime.now(), skewSeconds)
                .isLessThan(60);
    }

    @Test
    @DisplayName("DB 生成的 create_time 与 Java 生成的 expire_time 必须同一时钟")
    void 订单时间字段同源() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        OrderVO order = orderService.detail(vo.getOrderNo(), userId);

        // expire_time 由 Java 计算（now + payTimeout=15m），create_time 由列 DEFAULT 生成。
        // 两者之差必须约等于 15 分钟；若 DB 在 UTC，差值会变成 8h15m。
        long gapMinutes = Duration.between(order.getCreateTime(), order.getExpireTime()).toMinutes();
        assertThat(gapMinutes)
                .as("create_time=%s expire_time=%s 相差 %d 分钟，应约为 15",
                        order.getCreateTime(), order.getExpireTime(), gapMinutes)
                .isBetween(14L, 16L);

        assertThat(order.getRemainSeconds())
                .as("剩余支付秒数应落在 0~900 之间；时区错时会算出负数或极大值")
                .isBetween(0L, 900L);
    }

    @Test
    @DisplayName("DB 生成的 cancel_time 也必须与应用同源")
    void 取消时间同源() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        LocalDateTime before = LocalDateTime.now();
        orderService.cancel(vo.getOrderNo(), userId);

        LocalDateTime cancelTime = jdbc.queryForObject(
                "SELECT cancel_time FROM t_order WHERE order_no = ?",
                LocalDateTime.class, vo.getOrderNo());

        assertThat(cancelTime)
                .as("cancel_time 由 NOW(3) 生成，必须落在调用前后的时间窗内")
                .isBetween(before.minusMinutes(1), LocalDateTime.now().plusMinutes(1));
    }
}
