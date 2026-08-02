package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.ConsumedMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ConsumedMessageMapper extends BaseMapper<ConsumedMessage> {

    @Update("UPDATE consumed_message SET status = 'CONSUMED', consumed_at = #{consumedAt}, last_error = NULL " +
            "WHERE consumer_group = #{consumerGroup} AND message_id = #{messageId}")
    int markConsumed(@Param("consumerGroup") String consumerGroup, @Param("messageId") String messageId,
                     @Param("consumedAt") LocalDateTime consumedAt);

    @Update("UPDATE consumed_message SET status = 'FAILED', last_error = #{error} " +
            "WHERE consumer_group = #{consumerGroup} AND message_id = #{messageId}")
    int markFailed(@Param("consumerGroup") String consumerGroup, @Param("messageId") String messageId,
                   @Param("error") String error);

    @Update("UPDATE consumed_message SET status = 'PROCESSING', last_error = NULL, updated_at = NOW() " +
            "WHERE consumer_group = #{consumerGroup} AND message_id = #{messageId} " +
            "AND (status = 'FAILED' OR (status = 'PROCESSING' AND updated_at < #{staleBefore}))")
    int reclaimRetryable(@Param("consumerGroup") String consumerGroup,
                         @Param("messageId") String messageId,
                         @Param("staleBefore") LocalDateTime staleBefore);
}
