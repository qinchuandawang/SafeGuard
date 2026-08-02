package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEventService {

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectProvider<TransactionMQProducer> rocketProducerProvider;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${infra.redis.task-ttl-seconds:1800}")
    private long taskTtlSeconds;

    @Value("${infra.rocketmq.enabled:false}")
    private boolean rocketEnabled;

    @Value("${infra.rocketmq.topic:safeguard-task-events}")
    private String topic;

    @Value("${infra.rocketmq.task-tag:TaskEvent}")
    private String tag;

    public <T> T execute(String taskId, String event, Map<String, Object> payload,
                         Supplier<T> localOperation) {
        return execute(taskId, event, payload, localOperation, ignored -> true);
    }

    /**
     * 先发送 RocketMQ 半消息，再在监听器中执行数据库本地事务。
     * 当业务条件更新未命中时回滚半消息，避免发布不存在的状态变更。
     */
    public <T> T execute(String taskId, String event, Map<String, Object> payload,
                         Supplier<T> localOperation, Predicate<T> shouldCommit) {
        String messageId = UUID.randomUUID().toString();
        String json = serialize(messageId, taskId, event, payload);
        TaskEventTransactionContext<T> context = new TaskEventTransactionContext<>(
                messageId, taskId, event, localOperation, shouldCommit);

        if (!rocketEnabled) {
            executeWithoutRocket(context);
            if (context.isCommitted()) cacheTaskSnapshot(taskId, json);
            return context.resultOrThrow();
        }

        TransactionMQProducer producer = rocketProducerProvider.getIfAvailable();
        if (producer == null) {
            throw new IllegalStateException("RocketMQ 事务消息生产者不可用");
        }
        Message message = new Message(topic, tag, taskId + ":" + event,
                json.getBytes(StandardCharsets.UTF_8));
        message.putUserProperty("businessMessageId", messageId);
        message.putUserProperty("taskId", taskId);
        message.putUserProperty("eventType", event);
        try {
            TransactionSendResult result = producer.sendMessageInTransaction(message, context);
            context.throwIfFailed();
            if (!context.isExecuted()) {
                throw new IllegalStateException("RocketMQ 半消息未成功写入，本地事务未执行");
            }
            if (!context.isCommitted()) return context.getResult();
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK
                    || result.getLocalTransactionState() != LocalTransactionState.COMMIT_MESSAGE) {
                throw new IllegalStateException("RocketMQ 事务消息未确认提交: "
                        + (result == null ? "null" : result.getLocalTransactionState()));
            }
            cacheTaskSnapshot(taskId, json);
            log.debug("RocketMQ 事务消息已提交: taskId={}, event={}, msgId={}",
                    taskId, event, result.getMsgId());
            return context.getResult();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("RocketMQ 事务消息发送失败", exception);
        }
    }

    public String getCachedSnapshot(String taskId) {
        if (!redisEnabled) return null;
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            return redisTemplate == null ? null : redisTemplate.opsForValue().get(taskKey(taskId));
        } catch (Exception exception) {
            log.debug("读取 Redis 任务快照失败: taskId={}, error={}", taskId, exception.getMessage());
            return null;
        }
    }

    private <T> void executeWithoutRocket(TaskEventTransactionContext<T> context) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            context.executeLocalOperation();
            if (!context.isAccepted()) status.setRollbackOnly();
        });
        if (context.isAccepted() && context.getFailure() == null) context.markCommitted();
    }

    private String serialize(String messageId, String taskId, String event, Map<String, Object> payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", messageId);
        message.put("taskId", taskId);
        message.put("event", event);
        message.put("occurredAt", LocalDateTime.now().toString());
        message.put("payload", payload == null ? Map.of() : payload);
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception exception) {
            throw new IllegalStateException("任务事件序列化失败", exception);
        }
    }

    private void cacheTaskSnapshot(String taskId, String json) {
        if (!redisEnabled) return;
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                redisTemplate.opsForValue().set(taskKey(taskId), json, Duration.ofSeconds(taskTtlSeconds));
            }
        } catch (Exception exception) {
            log.debug("写入 Redis 任务快照失败: taskId={}, error={}", taskId, exception.getMessage());
        }
    }

    private String taskKey(String taskId) {
        return "safeguard:task:" + taskId;
    }
}
