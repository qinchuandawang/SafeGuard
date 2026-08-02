package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.MqTransactionRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface MqTransactionRecordMapper extends BaseMapper<MqTransactionRecord> {

    @Select("SELECT COUNT(*) FROM mq_transaction_record WHERE message_id = #{messageId} " +
            "AND transaction_status = 'COMMITTED'")
    long countCommitted(@Param("messageId") String messageId);

    @Delete("DELETE FROM mq_transaction_record WHERE created_at < #{before} LIMIT #{limit}")
    int deleteExpired(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
