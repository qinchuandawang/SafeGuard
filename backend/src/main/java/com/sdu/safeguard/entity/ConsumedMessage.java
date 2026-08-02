package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@TableName("consumed_message")
public class ConsumedMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("consumer_group")
    private String consumerGroup;
    @TableField("message_id")
    private String messageId;
    @TableField("task_id")
    private String taskId;
    private String status;
    @TableField("last_error")
    private String lastError;
    @TableField("consumed_at")
    private LocalDateTime consumedAt;
}
