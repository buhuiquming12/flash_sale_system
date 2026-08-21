package com.fss.mq;

import com.fss.infra.config.FssProperties;
import com.fss.infra.mq.MqTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Topic / 消费组名的一致性检查。<b>不需要任何容器</b>，是一个纯代码断言。
 *
 * <h3>为什么这个测试是必要的</h3>
 * 系统里有两套 Topic 名字的来源，而它们没法合并：
 * <ul>
 *   <li>消费端用 {@link MqTopics} 的常量——{@code @RocketMQMessageListener} 的
 *       {@code topic} 是注解属性，必须是编译期常量。</li>
 *   <li>生产端用 {@link FssProperties.Mq}——发送方要能在不改代码的前提下切 Topic
 *       （灰度、多环境共用一个 broker）。</li>
 * </ul>
 * 两边不一致的症状是<b>消息发出去了但没有任何消费者</b>：生产端一切正常、
 * 消费端一切正常、broker 上消息越积越多，而三方都不报错。
 * 加上测试环境的 {@code autoCreateTopicEnable = true}，一个拼错的名字还会被
 * 静默创建出来，连"Topic 不存在"这个提示都没有。
 *
 * <p>这类"两处必须一致"的约束，用一个五行的测试锁住比写在注释里靠人记得住。
 */
class MqTopicConsistencyTest {

    private final FssProperties props = new FssProperties();

    @Test
    @DisplayName("配置项默认值必须与消费端使用的常量逐字相同")
    void 配置与常量一致() {
        FssProperties.Mq.Topic topic = props.getMq().getTopic();
        assertThat(topic.getOrderCreate()).isEqualTo(MqTopics.ORDER_CREATE);
        assertThat(topic.getOrderClose()).isEqualTo(MqTopics.ORDER_CLOSE);
        assertThat(topic.getStockRelease()).isEqualTo(MqTopics.STOCK_RELEASE);
        assertThat(topic.getStockRollback()).isEqualTo(MqTopics.STOCK_ROLLBACK);

        FssProperties.Mq.Group group = props.getMq().getGroup();
        assertThat(group.getOrderCreate()).isEqualTo(MqTopics.GID_ORDER_CREATE);
        assertThat(group.getOrderClose()).isEqualTo(MqTopics.GID_ORDER_CLOSE);
        assertThat(group.getStockRelease()).isEqualTo(MqTopics.GID_STOCK_RELEASE);
        assertThat(group.getStockRollback()).isEqualTo(MqTopics.GID_STOCK_ROLLBACK);
    }

    @Test
    @DisplayName("死信 Topic 名必须是 %DLQ% + 消费组名，这个格式由 broker 决定")
    void 死信Topic名格式() {
        // 死信 Topic 不是我们起的名字，是 broker 按 %DLQ%<consumerGroup> 生成的。
        // 写错了的表现是死信队列没有消费者 —— 也就是"有 300 个用户扣了库存却没订单，
        // 而没人知道"，正是死信处理器存在的全部意义
        assertThat(MqTopics.DLQ_ORDER_CREATE)
                .isEqualTo("%DLQ%" + MqTopics.GID_ORDER_CREATE);
    }
}
