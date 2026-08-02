package com.sdu.safeguard.config;

import com.sdu.safeguard.service.TaskEventTransactionListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RocketMqConfig {

    @Bean(name = "safeguardInferenceWorkProducer", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "infra.rocketmq", name = "enabled", havingValue = "true")
    public DefaultMQProducer safeguardInferenceWorkProducer(
            @Value("${infra.rocketmq.name-server:localhost:9876}") String nameServer,
            @Value("${infra.rocketmq.work-producer-group:safeguard-inference-work-producer}") String producerGroup,
            @Value("${infra.rocketmq.send-timeout-ms:3000}") int sendTimeoutMs,
            @Value("${infra.rocketmq.retry-times:2}") int retryTimes) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        producer.setSendMsgTimeout(sendTimeoutMs);
        producer.setRetryTimesWhenSendFailed(retryTimes);
        producer.start();
        log.info("RocketMQ 推理工作生产者已启动: nameServer={}, producerGroup={}", nameServer, producerGroup);
        return producer;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "infra.rocketmq", name = "enabled", havingValue = "true")
    public TransactionMQProducer safeguardTaskEventProducer(
            @Value("${infra.rocketmq.name-server:localhost:9876}") String nameServer,
            @Value("${infra.rocketmq.producer-group:safeguard-task-event-tx-producer}") String producerGroup,
            @Value("${infra.rocketmq.send-timeout-ms:3000}") int sendTimeoutMs,
            @Value("${infra.rocketmq.retry-times:2}") int retryTimes,
            TaskEventTransactionListener transactionListener) throws Exception {
        TransactionMQProducer producer = new TransactionMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        producer.setSendMsgTimeout(sendTimeoutMs);
        producer.setRetryTimesWhenSendFailed(retryTimes);
        producer.setTransactionListener(transactionListener);
        producer.setCheckThreadPoolMinSize(2);
        producer.setCheckThreadPoolMaxSize(8);
        producer.setCheckRequestHoldMax(2000);
        producer.start();
        log.info("RocketMQ 事务消息生产者已启动: nameServer={}, producerGroup={}", nameServer, producerGroup);
        return producer;
    }
}
