package com.fss.infra.mq;

import com.fss.infra.config.FssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * RocketMQ 发送的最薄封装：只管"把一条消息发出去"，不知道本地消息表的存在。
 *
 * <p><b>为什么和 {@code ReliableMqProducer} 分成两个类</b>：可靠投递需要
 * {@code MqMessageMapper}，那是 {@code fss-domain} 的东西，而 {@code fss-infra}
 * 按分层只依赖 {@code fss-common}（见 docs/01）。让 infra 依赖 domain 就把
 * 「技术设施」和「数据模型」的方向拧反了，以后 domain 想用 infra 的工具就会成环。
 *
 * <p>所以切在这里：发送动作（技术细节、要不要定时、超时多少）留在 infra，
 * 落库与重试编排（业务语义、失败了要不要回补库存）放在 biz。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqSender {

    private final RocketMQTemplate template;
    private final FssProperties    props;

    /**
     * 同步发送。
     *
     * @param deliverTime 定时投递时刻，null 表示即时投递
     * @throws IllegalStateException 发送状态不是 SEND_OK
     */
    public void send(String topic, String bizKey, String body, LocalDateTime deliverTime) {
        Message<String> m = MessageBuilder.withPayload(body)
                // KEYS 让 RocketMQ 控制台能按 requestNo 反查这条消息。
                // 排查"用户有资格但没订单"时，这是从应用日志跳到消息的唯一入口
                .setHeader(RocketMQHeaders.KEYS, bizKey)
                .build();

        SendResult sr;
        if (deliverTime == null) {
            sr = template.syncSend(topic, m, props.getMq().getSendTimeout());
        } else {
            // syncSendDeliverTimeMills = 5.x 的任意时刻定时消息（决策 3）。
            // 4.x 只有 18 个固定延迟级别、没有 15 分钟这一档，关单时长会被中间件绑死。
            // broker.conf 的 timerWheelEnable=true 是它的前提，关掉之后这个调用
            // 不会报错，只会立刻投递——关单变成"下单即关单"，而且没有任何异常
            sr = template.syncSendDeliverTimeMills(topic, m, toMillis(deliverTime));
        }
        if (sr == null || sr.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("发送状态异常: "
                    + (sr == null ? "null" : sr.getSendStatus()));
        }
    }

    /** 即时消息异步发送；回调异常由上层吞掉，绝不能逃逸到 MQ 回调线程。 */
    public void sendAsync(String topic, String bizKey, String body, SendCallback callback) {
        Message<String> m = MessageBuilder.withPayload(body)
                .setHeader(RocketMQHeaders.KEYS, bizKey)
                .build();
        template.asyncSend(topic, m, callback, props.getMq().getSendTimeout());
    }

    private static long toMillis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
