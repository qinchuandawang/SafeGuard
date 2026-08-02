package com.sdu.safeguard.service;

import com.sdu.safeguard.entity.MqTransactionRecord;
import com.sdu.safeguard.mapper.MqTransactionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventTransactionListener implements TransactionListener {

    private final MqTransactionRecordMapper transactionRecordMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${infra.rocketmq.transaction.record-retention-days:7}")
    private long recordRetentionDays;

    @Value("${infra.rocketmq.transaction.cleanup-batch-size:1000}")
    private int cleanupBatchSize;

    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object argument) {
        if (!(argument instanceof TaskEventTransactionContext<?> context)) {
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            Boolean committed = template.execute(status -> {
                context.executeLocalOperation();
                if (!context.isAccepted()) {
                    status.setRollbackOnly();
                    return false;
                }
                transactionRecordMapper.insert(MqTransactionRecord.builder()
                        .messageId(context.getMessageId())
                        .taskId(context.getTaskId())
                        .eventType(context.getEventType())
                        .transactionStatus("COMMITTED")
                        .createdAt(LocalDateTime.now())
                        .build());
                return true;
            });
            if (Boolean.TRUE.equals(committed)) {
                context.markCommitted();
                return LocalTransactionState.COMMIT_MESSAGE;
            }
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (Exception exception) {
            context.markFailure(exception instanceof RuntimeException runtimeException
                    ? runtimeException : new IllegalStateException("RocketMQ 本地事务执行失败", exception));
            log.warn("RocketMQ 本地事务执行失败: taskId={}, event={}, error={}",
                    context.getTaskId(), context.getEventType(), exception.getMessage());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt message) {
        String messageId = message.getUserProperty("businessMessageId");
        if (messageId == null || messageId.isBlank()) {
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        try {
            return transactionRecordMapper.countCommitted(messageId) > 0
                    ? LocalTransactionState.COMMIT_MESSAGE
                    : LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (Exception exception) {
            log.warn("RocketMQ 本地事务回查失败: messageId={}, error={}", messageId, exception.getMessage());
            return LocalTransactionState.UNKNOW;
        }
    }

    @Scheduled(cron = "${infra.rocketmq.transaction.cleanup-cron:0 30 3 * * ?}")
    public void cleanupExpiredRecords() {
        try {
            int deleted = transactionRecordMapper.deleteExpired(
                    LocalDateTime.now().minusDays(recordRetentionDays), cleanupBatchSize);
            if (deleted > 0) log.info("清理 RocketMQ 本地事务记录: count={}", deleted);
        } catch (Exception exception) {
            log.warn("清理 RocketMQ 本地事务记录失败: {}", exception.getMessage());
        }
    }
}
