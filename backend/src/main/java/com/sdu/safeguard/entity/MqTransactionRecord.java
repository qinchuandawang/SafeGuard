package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("mq_transaction_record")
public class MqTransactionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String taskId;
    private String eventType;
    private String transactionStatus;
    private LocalDateTime createdAt;
}
