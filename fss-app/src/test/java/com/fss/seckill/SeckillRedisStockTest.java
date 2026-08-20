package com.fss.seckill;

import com.fss.biz.order.service.OrderService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.infra.redis.RedisKeys;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 侧库存回补验收。
 *
 * <p>两种回补语义在 Redis 上的区别是这批用例的重点：
 * <table>
 *   <tr><th>语义</th><th>触发</th><th>Redis 库存</th><th>Redis 购买标记</th></tr>
 *   <tr><td>RELEASE（脚本 C）</td><td>取消 / 超时关单</td><td>+qty</td><td><b>保留</b></td></tr>
 *   <tr><td>ROLLBACK（脚本 B）</td><td>预扣成功但订单没建成</td><td>+qty</td><td>按情况归还</td></tr>
 * </table>
 * 搞混的后果不是"数字差一点"，而是死循环或者超卖，见各用例的断言说明。
 */
class SeckillRedisStockTest extends IntegrationTestBase {

    @Autowired SeckillService    seckillService;
    @Autowired OrderService      orderService;
    @Autowired TestFixture       fixture;
    @Autowired StringRedisTemplate redis;

    @Test
    @DisplayName("R1 取消订单 → Redis 库存 +1，但购买标记保留（决策 1：取消后不可重抢）")
    void R1_取消回补库存但保留资格() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(9L);

        orderService.cancel(vo.getOrderNo(), userId);

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("库存要回池给其他用户")
                .isEqualTo(10L);
        assertThat(fixture.redisBought(act.activityId(), act.skuId(), userId))
                .as("购买标记必须保留。归还它就会出现：用户重抢 → Redis 放行 → "
                        + "DB 撞 uk_activity_sku_user（已取消的订单仍占着唯一键）→ "
                        + "回补 → 用户再抢，形成死循环")
                .isEqualTo(1L);

        // 对外表现就是重抢被拒，且这次拒绝发生在 Redis，没有走到数据库
        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_BOUGHT);
        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("被拒的重抢不能吃掉库存")
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("R2 重复关单 10 次 → Redis 库存只 +1（released Set 幂等）")
    void R2_重复回补幂等() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        for (int i = 0; i < 10; i++) {
            orderService.closeOrder(vo.getOrderNo(), "重复关单测试");
        }

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("DB 侧的 stock_released 条件更新只能保证 DB 不重复回补，"
                        + "管不住 Redis —— Redis 侧的幂等靠 released Set 的 SADD 返回值")
                .isEqualTo(10L);
        assertThat(redis.opsForSet().size(RedisKeys.released(act.activityId(), act.skuId())))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("R3 落库撞 ALREADY_BOUGHT → 只还库存不还资格，避免「失败→补偿→重抢」死循环")
    void R3_确定性失败不归还资格() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(9L);

        // 模拟 Redis 购买标记丢失（主从切换丢了 1 秒数据、或有人手动删了 key），
        // 而 DB 里那张订单还在。这是补偿回补最需要想清楚的一种输入
        redis.opsForHash().delete(RedisKeys.bought(act.activityId(), act.skuId()),
                String.valueOf(userId));
        assertThat(fixture.redisBought(act.activityId(), act.skuId(), userId)).isZero();

        // Lua 放行（它看不到标记了）→ 预扣成功 → 落库撞 uk_activity_sku_user
        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_BOUGHT);

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("预扣的那一份必须还回来，否则 1000 库存的活动最后只卖出 999 件"
                        + "而账面显示已售罄")
                .isEqualTo(9L);
        assertThat(fixture.redisBought(act.activityId(), act.skuId(), userId))
                .as("资格绝不能还。还了的话用户下次重抢又会走完整条链路再失败一次，"
                        + "无限循环 —— 这正是设计文档的 rollback 脚本需要多一个 "
                        + "keepBought 参数的原因")
                .isEqualTo(1L);
        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(1);

        // 再抢一次，这次在 Redis 就被拦掉，不再消耗数据库
        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_BOUGHT);
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(9L);
    }

    @Test
    @DisplayName("R4 售罄后取消一单 → Redis 取消售罄标记，库存重新可抢")
    void R4_回补后取消售罄标记() {
        TestFixture.Activity act = fixture.createRunningActivity(1);
        long u1 = fixture.createUser();
        long u2 = fixture.createUser();

        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), u1);
        assertThat(fixture.redisGoodsStatus(act.activityId(), act.skuId())).isEqualTo(2);

        orderService.cancel(vo.getOrderNo(), u1);

        assertThat(fixture.redisGoodsStatus(act.activityId(), act.skuId()))
                .as("不把 status 改回 1 的话，库存回补了也没人抢得到 —— "
                        + "Lua 第二步就以「已售罄」快速失败了")
                .isEqualTo(1);
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(1L);

        // 另一个用户能抢到回池的库存（决策 1：原用户资格已消耗，库存归其他人）
        assertThat(seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), u2).getOrderNo())
                .isNotBlank();
    }

    @Test
    @DisplayName("R5 支付成功不动 Redis 库存：预扣时已经扣过，locked→sold 只是 DB 内部搬账")
    void R5_支付不重复扣Redis() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        Long before = fixture.redisStock(act.activityId(), act.skuId());
        assertThat(before).isEqualTo(9L);

        // 这里不真的走支付，只断言"Redis 库存的语义是可售量"这一点：
        // 秒杀成功时已经扣掉，之后订单在 待支付→已支付 之间怎么流转都与它无关。
        // 支付时再扣一次 Redis 会让可售量凭空少一份
        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).isEqualTo(9);
        assertThat(s.locked()).isEqualTo(1);
        assertThat((long) s.available())
                .as("同步链路下 Redis 可售量应与 DB available_stock 一致；"
                        + "阶段三异步化后 Redis 会低于 DB，差值就是排队中的量")
                .isEqualTo(before);
    }
}
