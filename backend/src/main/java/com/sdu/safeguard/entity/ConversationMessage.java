package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 会话消息的持久化事实记录，缓存失效后用于恢复上下文。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("conversation_message")
public class ConversationMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("message_id")
    private String messageId;
    @TableField("conversation_id")
    private String conversationId;
    private String role;
    private String content;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
