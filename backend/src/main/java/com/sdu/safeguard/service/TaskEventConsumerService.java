package com.sdu.safeguard.service;

import com.sdu.safeguard.entity.ConsumedMessage;
import com.sdu.safeguard.mapper.ConsumedMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TaskEventConsumerService {

    private final ConsumedMessageMapper consumedMessageMapper;

    /**
     * 幂等占位、业务处理和消费完成标记必须位于同一本地事务。
     */
    @Transactional
    public boolean consume(String consumerGroup, String messageId, String taskId) {
        if (!claim(consumerGroup, messageId, taskId)) {
            return false;
        }
        // 当前消费者负责审计落点，后续业务副作用必须放在此事务内。
        consumedMessageMapper.markConsumed(consumerGroup, messageId, LocalDateTime.now());
        return true;
    }

    private boolean claim(String group, String messageId, String taskId) {
        try {
            consumedMessageMapper.insert(ConsumedMessage.builder()
                    .consumerGroup(group)
                    .messageId(messageId)
                    .taskId(taskId)
                    .status("PROCESSING")
                    .build());
            return true;
        } catch (DuplicateKeyException duplicateKeyException) {
            return consumedMessageMapper.reclaimRetryable(
                    group, messageId, LocalDateTime.now().minusMinutes(5)) == 1;
        }
    }
}
