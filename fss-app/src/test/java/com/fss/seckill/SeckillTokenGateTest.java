package com.fss.seckill;

import com.fss.biz.mq.ReliableMqProducer;
import com.fss.biz.mq.StockRollbackFallback;
import com.fss.biz.seckill.core.SeckillCompensateService;
import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.biz.seckill.core.SeckillTokenService;
import com.fss.biz.seckill.core.UncertainRecorder;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.service.impl.SeckillServiceImpl;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillRequestMapper;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.config.FssProperties;
import com.fss.infra.degrade.DegradeSwitch;
import com.fss.infra.metrics.SeckillMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀提交的令牌闸门。
 *
 * <p>锁住的是这条规则：<b>不带令牌就必须拒绝，除非显式打开了
 * {@code fss.seckill.allow-tokenless-submit}。</b>
 *
 * <p>在此之前这段逻辑写的是"带了令牌才校验"，于是
 * {@code /api/seckill/do}（固定路径，从不设置 token）可以完全不经过令牌校验 ——
 * 一次性、绑定 userId+活动+SKU 这些性质全都不生效，令牌设计退化成一次多余的
 * Redis 往返。而这条入口此前唯一的约束是一行注释："生产应在网关屏蔽"。
 */
class SeckillTokenGateTest {

    /** 让 {@code trySeckill} 抛一个可识别的异常，用来证明"已经越过闸门走到了这里" */
    private static final String REACHED_EXECUTOR = "REACHED_EXECUTOR";

    private final SeckillExecutor          executor      = mock(SeckillExecutor.class);
    private final SeckillTokenService      tokenService  = mock(SeckillTokenService.class);
    private final DegradeSwitch            degradeSwitch = mock(DegradeSwitch.class);

    private SeckillServiceImpl service(boolean allowTokenlessSubmit) {
        FssProperties props = new FssProperties();
        props.getSeckill().setAllowTokenlessSubmit(allowTokenlessSubmit);

        when(degradeSwitch.seckillEnabled()).thenReturn(true);
        when(executor.trySeckill(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), any()))
                .thenThrow(new IllegalStateException(REACHED_EXECUTOR));

        return new SeckillServiceImpl(executor, tokenService,
                mock(SeckillCompensateService.class), mock(UncertainRecorder.class),
                mock(ReliableMqProducer.class), mock(OrderMapper.class),
                mock(SeckillRequestMapper.class), degradeSwitch, mock(SeckillMetrics.class),
                mock(AlarmService.class), props, mock(StockRollbackFallback.class));
    }

    private static SeckillCmd cmd(String token) {
        SeckillCmd cmd = SeckillCmd.of(1001L, 2001L, 1);
        cmd.setToken(token);
        return cmd;
    }

    @Test
    @DisplayName("默认配置：不带令牌必须被拒，且不触碰令牌服务与 Lua")
    void 默认拒绝无令牌提交() {
        assertThatThrownBy(() -> service(false).submit(cmd(null), 42L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(tokenService, never()).verifyAndConsume(any(), anyLong(), anyLong(), anyLong());
        verify(executor, never()).trySeckill(anyLong(), anyLong(), anyLong(), anyInt(),
                anyString(), any());
    }

    @Test
    @DisplayName("空字符串令牌与 null 同样被拒——不能靠传空串绕过")
    void 空白令牌同样被拒() {
        assertThatThrownBy(() -> service(false).submit(cmd("   "), 42L))
                .isInstanceOf(BizException.class);
        verify(tokenService, never()).verifyAndConsume(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("显式打开开关后放行——压测与演示路径保留")
    void 开关打开后允许无令牌提交() {
        assertThatThrownBy(() -> service(true).submit(cmd(null), 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REACHED_EXECUTOR);
    }

    @Test
    @DisplayName("带令牌时无论开关如何都要消费令牌")
    void 带令牌仍然校验() {
        assertThatThrownBy(() -> service(false).submit(cmd("tok-abc"), 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REACHED_EXECUTOR);

        verify(tokenService).verifyAndConsume("tok-abc", 42L, 1001L, 2001L);
    }

    @Test
    @DisplayName("默认值必须是 false——默认姿态要安全")
    void 配置默认值为false() {
        assertThat(new FssProperties().getSeckill().isAllowTokenlessSubmit())
                .as("默认值被改成 true 会让所有部署静默失去令牌防护")
                .isFalse();
    }
}
