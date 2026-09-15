package com.fss.biz.seckill.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回补结果的三态映射。
 *
 * <p>这条映射是整个告警分级的地基：{@code FAILED} 走 P1（库存可能泄漏），
 * 另外两态走 P2（正常）。映射写反一个方向，就会把"库存泄漏"降级成
 * "一次正常的重复投递"——而这正是改造之前实际发生的事：
 * 回补失败被记成 P2 并打上"已告警"。
 */
class RollbackOutcomeTest {

    @Test
    @DisplayName("脚本返回码 0 → DONE，1 → ALREADY_DONE")
    void 脚本返回码映射() {
        assertThat(SeckillExecutor.toOutcome(0L)).isEqualTo(RollbackOutcome.DONE);
        assertThat(SeckillExecutor.toOutcome(1L)).isEqualTo(RollbackOutcome.ALREADY_DONE);
    }

    @Test
    @DisplayName("未知返回码与 null 一律按失败处理——宁可多报也不能漏")
    void 未知返回码按失败处理() {
        assertThat(SeckillExecutor.toOutcome(null))
                .as("拿不到返回值说明调用本身出了问题，不能当成幂等命中放过")
                .isEqualTo(RollbackOutcome.FAILED);
        assertThat(SeckillExecutor.toOutcome(7L)).isEqualTo(RollbackOutcome.FAILED);
        assertThat(SeckillExecutor.toOutcome(-1L)).isEqualTo(RollbackOutcome.FAILED);
    }

    @Test
    @DisplayName("只有 FAILED 需要告警")
    void 只有失败态需要告警() {
        assertThat(RollbackOutcome.FAILED.isFailed()).isTrue();
        assertThat(RollbackOutcome.DONE.isFailed()).isFalse();
        assertThat(RollbackOutcome.ALREADY_DONE.isFailed())
                .as("幂等命中是重复投递的正常结果，绝不能当成库存泄漏报警")
                .isFalse();
    }
}
