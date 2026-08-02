package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class DetectionWorkQueueService {

    private final ObjectProvider<DefaultMQProducer> producerProvider;
    private final ObjectMapper objectMapper;
    private final AsyncTaskMapper asyncTaskMapper;

    public DetectionWorkQueueService(
            @Qualifier("safeguardInferenceWorkProducer") ObjectProvider<DefaultMQProducer> producerProvider,
            ObjectMapper objectMapper,
            AsyncTaskMapper asyncTaskMapper) {
        this.producerProvider = producerProvider;
        this.objectMapper = objectMapper;
        this.asyncTaskMapper = asyncTaskMapper;
    }

    @Value("${infra.rocketmq.enabled:false}")
    private boolean enabled;

    @Value("${infra.rocketmq.work-topic:safeguard-inference-work}")
    private String topic;

    @Value("${infra.rocketmq.work-tag:VideoInference}")
    private String tag;

    @Value("${task.queue.max-video-queued:20}")
    private int maxVideoQueued;

    /**
     * 发送前检查可接受的排队上限。超限时任务仍保持 queued，调用方返回 503，
     * 后续可通过同一个幂等键重试或由恢复任务重新投递。
     */
    public void enqueueVideo(String taskId) {
        AsyncTask task = asyncTaskMapper.findByTaskId(taskId);
        if (task == null) {
            throw new IllegalStateException("推理任务不存在: " + taskId);
        }
        if (!"queued".equals(task.getStatus())) {
            return;
        }
        if (asyncTaskMapper.countActiveByType("video") > maxVideoQueued) {
            throw new TaskQueueFullException("视频推理排队容量已满，请稍后重试");
        }
        if (!enabled) {
            throw new IllegalStateException("RocketMQ 工作队列未启用，无法投递视频推理任务");
        }
        DefaultMQProducer producer = producerProvider.getIfAvailable();
        if (producer == null) {
            throw new IllegalStateException("RocketMQ 工作生产者不可用");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", task.getTaskId());
            payload.put("type", task.getType());
            payload.put("modelId", task.getModelId());
            payload.put("filePath", task.getFilePath());
            payload.put("objectKey", task.getObjectKey());
            payload.put("fileHash", task.getFileHash());
            String body = objectMapper.writeValueAsString(payload);
            Message message = new Message(topic, tag, task.getTaskId(),
                    body.getBytes(StandardCharsets.UTF_8));
            message.putUserProperty("taskId", task.getTaskId());
            message.putUserProperty("workType", "video-inference");
            SendResult result = producer.send(message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ 未确认工作消息写入成功");
            }
            log.info("视频推理工作消息已投递: taskId={}, msgId={}, status={}",
                    task.getTaskId(), result.getMsgId(), result.getSendStatus());
        } catch (Exception exception) {
            throw new IllegalStateException("视频推理工作消息投递失败", exception);
        }
    }
}
