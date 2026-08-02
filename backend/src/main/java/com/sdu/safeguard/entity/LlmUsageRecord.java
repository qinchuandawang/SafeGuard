package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_usage_record")
public class LlmUsageRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("request_id")
    private String requestId;
    private String scene;
    private String model;
    @TableField("prompt_tokens")
    private Integer promptTokens;
    @TableField("completion_tokens")
    private Integer completionTokens;
    @TableField("total_tokens")
    private Integer totalTokens;
    @TableField("cache_hit")
    private Boolean cacheHit;
    private String status;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
