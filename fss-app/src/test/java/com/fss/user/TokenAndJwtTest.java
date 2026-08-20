package com.fss.user;

import com.fss.biz.seckill.core.SeckillTokenService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.biz.user.core.JwtService;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀令牌与 JWT 主动失效验收。
 *
 * <p>这两件事都是"Redis 接入之后才有可能做到"的：
 * 令牌需要一个能原子取出并删除的存储（{@code GETDEL}），
 * JWT 撤销需要服务端为无状态的 token 保留一份状态。
 */
class TokenAndJwtTest extends IntegrationTestBase {

    @Autowired SeckillTokenService tokenService;
    @Autowired SeckillService      seckillService;
    @Autowired JwtService          jwtService;
    @Autowired TestFixture         fixture;

    @Test
    @DisplayName("T1 令牌只能用一次：同一个令牌第二次提交被拒")
    void T1_令牌一次性() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        String token = tokenService.issue(userId, act.activityId(), act.skuId());
        tokenService.verifyAndConsume(token, userId, act.activityId(), act.skuId());

        assertThatThrownBy(() -> tokenService.verifyAndConsume(
                token, userId, act.activityId(), act.skuId()))
                .as("用 GET 再 DELETE 的写法会留一个并发窗口：脚本拿同一个令牌"
                        + "并发打 100 次，全部都能在 DELETE 之前通过 GET 校验。"
                        + "GETDEL 是原子的，第二次必然拿到 null")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_TOKEN_INVALID);
    }

    @Test
    @DisplayName("T2 令牌与 (用户, 活动, SKU) 绑定：别人的令牌用不了，自己的不受影响")
    void T2_令牌绑定身份() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long owner = fixture.createUser();
        long other = fixture.createUser();

        String token = tokenService.issue(owner, act.activityId(), act.skuId());

        // 令牌 key 里带了 userId，other 拿着 owner 的令牌串也拼不出 owner 的 key，
        // 它查的是自己那把（不存在的）钥匙
        assertThatThrownBy(() -> tokenService.verifyAndConsume(
                token, other, act.activityId(), act.skuId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_TOKEN_INVALID);

        // 别人的失败尝试不会消费掉 owner 的令牌
        tokenService.verifyAndConsume(token, owner, act.activityId(), act.skuId());
    }

    @Test
    @DisplayName("T3 令牌串错误也会消费掉令牌 —— GETDEL 无法先比对再删除，这是有意的取舍")
    void T3_错误令牌也消费() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        String token = tokenService.issue(userId, act.activityId(), act.skuId());

        assertThatThrownBy(() -> tokenService.verifyAndConsume(
                "wrong-token", userId, act.activityId(), act.skuId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_TOKEN_INVALID);

        assertThatThrownBy(() -> tokenService.verifyAndConsume(
                token, userId, act.activityId(), act.skuId()))
                .as("""
                        GETDEL 是取出并删除，没有"比对不上就别删"这个选项。
                        想保住令牌就得改成 GET 比对再 DELETE，而那样会开一个并发窗口：
                        脚本拿同一个令牌并发打 100 次，全部都能在 DELETE 之前通过 GET 校验，
                        令牌的一次性彻底失效。
                        代价这一侧：带错令牌的用户需要重新领一次。而 key 是由已认证的
                        userId 推出来的，攻击者只能作废自己的令牌，作废不了别人的 ——
                        所以这个代价只落在自己身上，可以接受""")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_TOKEN_INVALID);
    }

    @Test
    @DisplayName("T4 带令牌走完整条秒杀链路；令牌无效时秒杀直接失败，不消耗库存")
    void T4_令牌参与秒杀() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        SeckillCmd bad = SeckillCmd.of(act.activityId(), act.skuId(), 1);
        bad.setToken("not-a-real-token");
        assertThatThrownBy(() -> seckillService.submit(bad, userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_TOKEN_INVALID);
        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("令牌校验在 Lua 之前，被拒的请求不该碰库存")
                .isEqualTo(10L);

        SeckillCmd good = SeckillCmd.of(act.activityId(), act.skuId(), 1);
        good.setToken(tokenService.issue(userId, act.activityId(), act.skuId()));
        assertThat(seckillService.submit(good, userId).getOrderNo()).isNotBlank();
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(9L);
    }

    @Test
    @DisplayName("J1 JWT 签发即登记、撤销即失效（阶段一的 Noop 实现做不到这一点）")
    void J1_JWT可主动失效() {
        long userId = fixture.createUser();
        JwtService.Issued issued = jwtService.issue(userId, "u" + userId, 0);

        JwtService.Payload p = jwtService.verify(issued.token());
        assertThat(p.userId()).isEqualTo(userId);

        jwtService.revoke(issued.jti());

        assertThatThrownBy(() -> jwtService.verify(issued.token()))
                .as("签名和过期时间都还是合法的，被拒的唯一原因是服务端撤销了 jti。"
                        + "阶段一没有 Redis，登出只是客户端丢弃 token，"
                        + "服务端仍认它到过期 —— 那是一个真实的安全缺口")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("J2 撤销一个会话不影响同一用户的其他会话")
    void J2_按会话撤销() {
        long userId = fixture.createUser();
        JwtService.Issued phone = jwtService.issue(userId, "u" + userId, 0);
        JwtService.Issued pc    = jwtService.issue(userId, "u" + userId, 0);
        assertThat(phone.jti()).isNotEqualTo(pc.jti());

        jwtService.revoke(phone.jti());

        assertThatThrownBy(() -> jwtService.verify(phone.token()))
                .isInstanceOf(BizException.class);
        assertThat(jwtService.verify(pc.token()).userId())
                .as("白名单按 jti 存，粒度是会话而不是用户；"
                        + "手机上退出登录不该把电脑上也踢下线")
                .isEqualTo(userId);
    }

    @Test
    @DisplayName("J3 没签发过的 jti 一律无效，校验失败不区分原因")
    void J3_未登记的token无效() {
        long userId = fixture.createUser();
        JwtService.Issued issued = jwtService.issue(userId, "u" + userId, 0);
        jwtService.revoke(issued.jti());

        // 已撤销、签名错误、已过期，对外都是同一个 1002 —— 不给攻击者任何信息
        assertThatThrownBy(() -> jwtService.verify(issued.token()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> jwtService.verify("obviously.not.a.jwt"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
