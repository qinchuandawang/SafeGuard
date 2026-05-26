package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 综合检测记录表
 */
@TableName("detection_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务ID */
    @TableField("task_id")
    private String taskId;

    /** 用户ID (预留) */
    @TableField("user_id")
    private Long userId;

    /** 检测类型: audio / video / text / multimodal */
    @TableField("detection_type")
    private String detectionType;

    /** 文件名 (音频/视频检测) */
    @TableField("file_name")
    private String fileName;

    /** 文件路径 */
    @TableField("file_path")
    private String filePath;

    /** 检测状态: pending / processing / completed / failed */
    @TableField("status")
    private String status;

    /** 检测结果: safe / suspicious / dangerous */
    @TableField("result")
    private String result;

    /** 风险分数 (0-100) */
    @TableField("risk_score")
    private Integer riskScore;

    /** 伪造概率 (音频/视频) */
    @TableField("spoof_probability")
    private Double spoofProbability;

    /** 详细分析结果 (JSON格式) */
    @TableField("analysis_detail")
    private String analysisDetail;

    /** 错误信息 */
    @TableField("error_message")
    private String errorMessage;

    /** 检测耗时(毫秒) */
    @TableField("processing_time_ms")
    private Long processingTimeMs;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 完成时间 */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除标记 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
