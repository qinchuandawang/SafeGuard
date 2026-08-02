package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TaskEventServiceTest {

    @Mock
    private ObjectProvider<StringRedisTemplate> redisProvider;
    @Mock
    private ObjectProvider<TransactionMQProducer> producerProvider;
    @Mock
    private TransactionMQProducer producer;
    @Mock
    private PlatformTransactionManager transactionManager;

    private TaskEventService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new TaskEventService(redisProvider, producerProvider,
                new ObjectMapper(), transactionManager);
        ReflectionTestUtils.setField(service, "redisEnabled", false);
        ReflectionTestUtils.setField(service, "rocketEnabled", false);
        ReflectionTestUtils.setField(service, "topic", "topic");
        ReflectionTestUtils.setField(service, "tag", "tag");
    }

    @Test
    void 未启用RocketMQ时仍在本地事务中执行业务更新() {
        AtomicInteger executions = new AtomicInteger();

        Integer result = service.execute("task-1", "created", Map.of("status", "queued"),
                executions::incrementAndGet);

        assertThat(result).isEqualTo(1);
        assertThat(executions).hasValue(1);
    }

    @Test
    void 条件更新未命中时不提交事件() {
        Integer result = service.execute("task-1", "progress", Map.of("progress", 20),
                () -> 0, affected -> affected == 1);

        assertThat(result).isZero();
    }

    @Test
    void 半消息未写入时不得执行本地事务() throws Exception {
        ReflectionTestUtils.setField(service, "rocketEnabled", true);
        when(producerProvider.getIfAvailable()).thenReturn(producer);
        when(producer.sendMessageInTransaction(any(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.execute("task-1", "created", Map.of(), () -> 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("半消息未成功写入");
    }
}
