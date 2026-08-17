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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("memory_fact_event")
public class MemoryFactEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("event_id")
    private String eventId;
    @TableField("session_id")
    private String sessionId;
    @TableField("owner_key")
    private String ownerKey;
    @TableField("fact_key")
    private String factKey;
    @TableField("fact_type")
    private String factType;
    @TableField("fact_value")
    private String factValue;
    private String content;
    private String summary;
    private String source;
    private Double confidence;
    private Double importance;
    private Integer version;
    private String status;
    @TableField("supersedes_event_id")
    private String supersedesEventId;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
