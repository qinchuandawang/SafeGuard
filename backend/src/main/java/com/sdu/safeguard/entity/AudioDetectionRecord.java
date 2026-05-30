package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 音频检测记录表
 */
@TableName("audio_detection_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioDetectionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务ID (对应 DetectionTask) */
    @TableField("task_id")
    private String taskId;

    /** 用户ID (预留) */
    @TableField("user_id")
    private Long userId;

    /** 原始文件名 */
    @TableField("file_name")
    private String fileName;

    /** 文件大小(字节) */
    @TableField("file_size")
    private Long fileSize;

    /** 文件存储路径 */
    @TableField("file_path")
    private String filePath;

    /** 检测结果: bonafide (真实) / spoof (伪造) */
    @TableField("detection_result")
    @Builder.Default
    private String detectionResult = "pending";

    /** 伪造概率 */
    @TableField("spoof_probability")
    private Double spoofProbability;

    /** 真实概率 */
    @TableField("bonafide_probability")
    private Double bonafideProbability;

    /** 置信度 */
    @TableField("confidence")
    private Double confidence;

    /** 风险等级: low / medium / high */
    @TableField("risk_level")
    private String riskLevel;

    /** 检测模型版本 */
    @TableField("model_version")
    private String modelVersion;

    /** 检测耗时(毫秒) */
    @TableField("detection_latency_ms")
    private Double detectionLatencyMs;

    /** 检测状态: pending / success / failed */
    @TableField("status")
    private String status;

    /** 错误信息 */
    @TableField("error_message")
    private String errorMessage;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除标记 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
