package com.sdu.safeguard.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdu.safeguard.service.TaskEventConsumerService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import com.sdu.safeguard.service.DetectionTaskManager;
import com.sdu.safeguard.service.InferenceCapacityExceededException;
import com.sdu.safeguard.service.VideoTaskExecutionService;

@Slf4j
@Configuration
public class RocketMqConsumerConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "infra.rocketmq", name = "enabled", havingValue = "true")
    public DefaultMQPushConsumer inferenceWorkConsumer(
            @Value("${infra.rocketmq.name-server:localhost:9876}") String nameServer,
            @Value("${infra.rocketmq.work-consumer-group:safeguard-inference-worker}") String consumerGroup,
            @Value("${infra.rocketmq.work-topic:safeguard-inference-work}") String topic,
            @Value("${infra.rocketmq.work-tag:VideoInference}") String tag,
            @Value("${infra.rocketmq.consumer.max-reconsume-times:5}") int maxReconsumeTimes,
            ObjectMapper objectMapper,
            AsyncTaskMapper asyncTaskMapper,
            DetectionTaskManager taskManager,
            VideoTaskExecutionService executionService,
            MeterRegistry meterRegistry) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(4);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(maxReconsumeTimes);
        consumer.setConsumeTimeout(30);
        consumer.subscribe(topic, tag);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (org.apache.rocketmq.common.message.MessageExt message : messages) {
                String taskId = message.getUserProperty("taskId");
                try {
                    if (taskId == null || taskId.isBlank()) {
                        taskId = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                                .path("taskId").asText(null);
                    }
                    AsyncTask task = taskId == null ? null : asyncTaskMapper.findByTaskId(taskId);
                    if (task == null || !"queued".equals(task.getStatus())) {
                        meterRegistry.counter("safeguard.inference.work.skipped").increment();
                        continue;
                    }
                    if (taskManager.markQueuedForProcessing(task)) {
                        executionService.execute(task);
                        meterRegistry.counter("safeguard.inference.work.completed").increment();
                    }
                } catch (InferenceCapacityExceededException capacityException) {
                    if (taskId != null) taskManager.requeueProcessing(taskId);
                    meterRegistry.counter("safeguard.inference.work.capacity_retry").increment();
                    log.info("推理容量不足，消息稍后重试: taskId={}, error={}", taskId, capacityException.getMessage());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                } catch (Exception exception) {
                    if (taskId != null && message.getReconsumeTimes() >= maxReconsumeTimes) {
                        taskManager.fail(taskId, exception.getMessage() == null ? "推理任务重试耗尽" : exception.getMessage());
                    } else if (taskId != null) {
                        taskManager.requeueProcessing(taskId);
                    }
                    meterRegistry.counter("safeguard.inference.work.failed").increment();
                    log.warn("推理工作消息执行失败: taskId={}", taskId, exception);
                    return message.getReconsumeTimes() >= maxReconsumeTimes
                            ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS
                            : ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("RocketMQ 推理工作消费者已启动: topic={}, consumerGroup={}", topic, consumerGroup);
        return consumer;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "infra.rocketmq", name = "enabled", havingValue = "true")
    public DefaultMQPushConsumer taskEventAuditConsumer(
            @Value("${infra.rocketmq.name-server:localhost:9876}") String nameServer,
            @Value("${infra.rocketmq.consumer-group:safeguard-task-event-audit}") String consumerGroup,
            @Value("${infra.rocketmq.topic:safeguard-task-events}") String topic,
            @Value("${infra.rocketmq.task-tag:TaskEvent}") String tag,
            @Value("${infra.rocketmq.consumer.max-reconsume-times:5}") int maxReconsumeTimes,
            TaskEventConsumerService taskEventConsumerService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(8);
        consumer.setPullBatchSize(16);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(maxReconsumeTimes);
        consumer.subscribe(topic, tag);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (org.apache.rocketmq.common.message.MessageExt message : messages) {
                String messageId = message.getKeys() == null || message.getKeys().isBlank()
                        ? message.getMsgId() : message.getKeys();
                String taskId = null;
                try {
                    JsonNode root = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
                    messageId = root.path("messageId").asText(messageId);
                    taskId = root.path("taskId").asText(null);
                    if (!taskEventConsumerService.consume(consumerGroup, messageId, taskId)) {
                        meterRegistry.counter("safeguard.mq.duplicate", "group", consumerGroup).increment();
                        continue;
                    }
                    meterRegistry.counter("safeguard.mq.consumed", "group", consumerGroup).increment();
                } catch (Exception exception) {
                    meterRegistry.counter("safeguard.mq.retry", "group", consumerGroup).increment();
                    log.warn("任务事件消费失败，将由 RocketMQ 重试: messageId={}, taskId={}", messageId, taskId, exception);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        return consumer;
    }

}
