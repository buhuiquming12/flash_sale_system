package com.fss.mq;

import com.fss.biz.mq.ReliableMqProducer;
import com.fss.domain.entity.MqMessage;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.mq.MqSender;
import com.fss.infra.mq.MqTopics;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReliableMqProducerAsyncTest {

    private final MqSender sender = mock(MqSender.class);
    private final MqMessageMapper mapper = mock(MqMessageMapper.class);
    private final ReliableMqProducer producer = new ReliableMqProducer(sender, mapper);

    @Test
    void orderCreate先落库再异步发送_成功回调推进已发送() {
        ArgumentCaptor<MqMessage> record = ArgumentCaptor.forClass(MqMessage.class);
        ArgumentCaptor<SendCallback> callback = ArgumentCaptor.forClass(SendCallback.class);

        producer.sendReliable(MqTopics.ORDER_CREATE, "R1", message(), null);

        verify(mapper).insert(record.capture());
        verify(sender).sendAsync(eq(MqTopics.ORDER_CREATE), eq("R1"), any(), callback.capture());
        SendResult ok = new SendResult();
        ok.setSendStatus(SendStatus.SEND_OK);
        callback.getValue().onSuccess(ok);
        verify(mapper).markSent(record.getValue().getMsgId());
    }

    @Test
    void 失败回调绝不抛并登记30秒退避() {
        ArgumentCaptor<MqMessage> record = ArgumentCaptor.forClass(MqMessage.class);
        ArgumentCaptor<SendCallback> callback = ArgumentCaptor.forClass(SendCallback.class);
        producer.sendReliable(MqTopics.ORDER_CREATE, "R2", message(), null);
        verify(mapper).insert(record.capture());
        verify(sender).sendAsync(eq(MqTopics.ORDER_CREATE), eq("R2"), any(), callback.capture());

        assertThatCode(() -> callback.getValue().onException(new RuntimeException("broker down")))
                .doesNotThrowAnyException();
        verify(mapper).markRetry(eq(record.getValue().getMsgId()), eq(30L), eq("broker down"));
    }

    @Test
    void 回调写库失败也不得逃逸回调线程() {
        ArgumentCaptor<MqMessage> record = ArgumentCaptor.forClass(MqMessage.class);
        ArgumentCaptor<SendCallback> callback = ArgumentCaptor.forClass(SendCallback.class);
        producer.sendReliable(MqTopics.ORDER_CREATE, "R3", message(), null);
        verify(mapper).insert(record.capture());
        verify(sender).sendAsync(eq(MqTopics.ORDER_CREATE), eq("R3"), any(), callback.capture());
        when(mapper.markSent(record.getValue().getMsgId())).thenThrow(new RuntimeException("db down"));

        SendResult ok = new SendResult();
        ok.setSendStatus(SendStatus.SEND_OK);
        assertThatCode(() -> callback.getValue().onSuccess(ok)).doesNotThrowAnyException();
        verify(mapper).markRetry(eq(record.getValue().getMsgId()), eq(30L), eq("db down"));
    }

    private static OrderCreateMessage message() {
        return OrderCreateMessage.builder().requestNo("R").userId(1L).activityId(2L)
                .skuId(3L).quantity(1).version(OrderCreateMessage.CURRENT_VERSION).build();
    }
}
