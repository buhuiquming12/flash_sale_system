package com.fss.biz.order.core;

import com.fss.common.enums.OrderStatus;
import com.fss.common.error.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.fss.common.enums.OrderStatus.CANCELLED;
import static com.fss.common.enums.OrderStatus.FINISHED;
import static com.fss.common.enums.OrderStatus.PAID;
import static com.fss.common.enums.OrderStatus.PENDING_PAY;
import static com.fss.common.enums.OrderStatus.REFUNDED;
import static com.fss.common.enums.OrderStatus.REFUNDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单状态机：全部合法与非法迁移。
 *
 * <p>穷举 6×6 = 36 种组合，而不是只测几条"正常路径"——
 * 状态机的价值恰好在于它拒绝了什么。
 */
class OrderStateMachineTest {

    @ParameterizedTest(name = "合法迁移 {0} → {1}")
    @CsvSource({
            "PENDING_PAY, PAID",
            "PENDING_PAY, CANCELLED",
            "PAID,        FINISHED",
            "PAID,        REFUNDING",
            "REFUNDING,   REFUNDED",
            "REFUNDING,   PAID",
            "FINISHED,    REFUNDING"})
    void 合法迁移应通过(OrderStatus from, OrderStatus to) {
        assertThat(OrderStateMachine.canTransfer(from, to)).isTrue();
        assertThatCode(() -> OrderStateMachine.assertTransfer(from, to)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("36 种组合中除 7 条合法迁移外全部应被拒绝")
    void 非法迁移应被拒绝() {
        int allowed = 0;
        int rejected = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                if (OrderStateMachine.canTransfer(from, to)) {
                    allowed++;
                } else {
                    rejected++;
                    assertThatThrownBy(() -> OrderStateMachine.assertTransfer(from, to))
                            .isInstanceOf(BizException.class);
                }
            }
        }
        assertThat(allowed).isEqualTo(7);
        assertThat(rejected).isEqualTo(29);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void 任何状态都不能迁移到自身(OrderStatus s) {
        assertThat(OrderStateMachine.canTransfer(s, s)).isFalse();
    }

    @Test
    void 已取消与已退款是终态() {
        assertThat(OrderStateMachine.isTerminal(CANCELLED)).isTrue();
        assertThat(OrderStateMachine.isTerminal(REFUNDED)).isTrue();

        assertThat(OrderStateMachine.isTerminal(PENDING_PAY)).isFalse();
        assertThat(OrderStateMachine.isTerminal(PAID)).isFalse();
        assertThat(OrderStateMachine.isTerminal(REFUNDING)).isFalse();
        assertThat(OrderStateMachine.isTerminal(FINISHED)).isFalse();
    }

    @Test
    @DisplayName("已取消订单不能被支付——这是关单与支付竞态的正确性前提")
    void 已取消不能再支付() {
        assertThat(OrderStateMachine.canTransfer(CANCELLED, PAID)).isFalse();
    }

    @Test
    @DisplayName("已支付订单不能被取消——否则超时关单会吞掉已付款的订单")
    void 已支付不能被取消() {
        assertThat(OrderStateMachine.canTransfer(PAID, CANCELLED)).isFalse();
    }

    @Test
    void 状态码与枚举双向映射一致() {
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(OrderStatus.of(s.code())).isSameAs(s);
        }
        // 显式编码，不依赖 ordinal
        assertThat(PENDING_PAY.code()).isZero();
        assertThat(PAID.code()).isEqualTo(1);
        assertThat(CANCELLED.code()).isEqualTo(2);
        assertThat(FINISHED.code()).isEqualTo(3);
        assertThat(REFUNDING.code()).isEqualTo(4);
        assertThat(REFUNDED.code()).isEqualTo(5);
    }

    @Test
    @DisplayName("只有已取消不占用库存，用于库存对账等式")
    void 库存占用口径() {
        assertThat(CANCELLED.occupiesStock()).isFalse();
        for (OrderStatus s : OrderStatus.values()) {
            if (s != CANCELLED) {
                assertThat(s.occupiesStock()).isTrue();
            }
        }
    }
}
