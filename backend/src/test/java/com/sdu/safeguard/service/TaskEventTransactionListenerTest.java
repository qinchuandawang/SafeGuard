package com.sdu.safeguard.service;

import com.sdu.safeguard.entity.MqTransactionRecord;
import com.sdu.safeguard.mapper.MqTransactionRecordMapper;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskEventTransactionListenerTest {

    @Mock
    private MqTransactionRecordMapper transactionRecordMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    private TaskEventTransactionListener listener;

    @BeforeEach
    void setUp() {
        listener = new TaskEventTransactionListener(transactionRecordMapper, transactionManager);
    }

    @Test
    void 本地事务与回查记录原子提交() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        TaskEventTransactionContext<Integer> context = new TaskEventTransactionContext<>(
                "message-1", "task-1", "created", () -> 1, affected -> affected == 1);

        LocalTransactionState state = listener.executeLocalTransaction(new Message(), context);

        assertThat(state).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        assertThat(context.isCommitted()).isTrue();
        verify(transactionRecordMapper).insert(any(MqTransactionRecord.class));
    }

    @Test
    void Broker根据本地事务记录完成回查() {
        MessageExt message = new MessageExt();
        message.putUserProperty("businessMessageId", "message-1");
        when(transactionRecordMapper.countCommitted("message-1")).thenReturn(1L);

        assertThat(listener.checkLocalTransaction(message))
                .isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
    }
}
