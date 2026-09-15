package com.fss.infra.mq;

/**
 * Topic 与消费组名的字面常量。
 *
 * <p><b>为什么不直接读 {@code FssProperties.Mq.Topic}</b>：
 * {@code @RocketMQMessageListener} 的 {@code topic} / {@code consumerGroup} 是注解属性，
 * 必须是编译期常量。注解里能写 {@code "${fss.mq.topic.order-create}"} 占位符
 * （RocketMQ 的 BeanPostProcessor 会做 {@code resolvePlaceholders}），
 * 但那样 Topic 名就散落在多个字符串字面量里，改一处漏一处不会有任何编译错误。
 *
 * <p>所以约定：<b>常量是唯一事实来源</b>，配置项的默认值必须与这里一致。
 * 生产者从 {@code FssProperties} 读（可运行时改），消费者用常量（编译期绑定）。
 * 两者不一致时消息发出去没人消费——一致性由单元测试
 * {@code MqTopicConsistencyTest} 锁住（做不到启动时断言：这个类是个只放常量的
 * 工具类，没有会被 Spring 实例化的时机），而不是等到压测时发现"消息全在积压"。
 */
public final class MqTopics {

    public static final String ORDER_CREATE   = "FSS_ORDER_CREATE";
    public static final String ORDER_CLOSE    = "FSS_ORDER_CLOSE";
    public static final String STOCK_RELEASE  = "FSS_STOCK_RELEASE";

    /**
     * 补偿回补主题。
     *
     * <p><b>代码里没有生产者。</b> 补偿目前走的是同步直调
     * （{@code SeckillCompensateService#rollback}）+ 库存对账兜底，
     * 没有任何路径往这个主题发消息（见 README「已知取舍」一节）。
     * 它保留下来是因为消费端 {@code StockRollbackListener} 仍然有效，
     * 可以由运维用 {@code mqadmin sendMessage} 手动重投，
     * 用来演练故障用例 F11（重复投递 10 次，库存只 +1）。
     *
     * <p>所以：不要以为发了 ORDER_CREATE 之外的消息就会自动回补；
     * 也不要因为"订阅了却一直没消息"去查生产端——它本来就没有。
     */
    public static final String STOCK_ROLLBACK = "FSS_STOCK_ROLLBACK";

    public static final String GID_ORDER_CREATE   = "GID_FSS_ORDER_CREATE";
    public static final String GID_ORDER_CLOSE    = "GID_FSS_ORDER_CLOSE";
    public static final String GID_STOCK_RELEASE  = "GID_FSS_STOCK_RELEASE";
    public static final String GID_STOCK_ROLLBACK = "GID_FSS_STOCK_ROLLBACK";
    public static final String GID_DLQ_HANDLER    = "GID_FSS_DLQ_HANDLER";

    /**
     * 死信 Topic 前缀。RocketMQ 把某个消费组重试耗尽的消息投到 {@code %DLQ%<group>}，
     * 名字由 broker 生成，不能自己起。
     */
    public static final String DLQ_PREFIX = "%DLQ%";

    public static final String DLQ_ORDER_CREATE = DLQ_PREFIX + GID_ORDER_CREATE;

    private MqTopics() {
    }
}
