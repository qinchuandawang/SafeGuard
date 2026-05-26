package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 音频模型表
 */
@TableName("audio_model")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型名称 */
    @TableField("name")
    private String name;

    /** 模型版本/路径 */
    @TableField("model_version")
    private String modelVersion;

    /** 模型类型: wav2vec2, xvect etc. */
    @TableField("model_type")
    private String modelType;

    /** 训练数据集 */
    @TableField("training_dataset")
    private String trainingDataset;

    /** 训练轮次 */
    @TableField("training_epochs")
    private Integer trainingEpochs;

    /** 准确率 */
    @TableField("accuracy")
    private Double accuracy;

    /** 等错误率 (Equal Error Rate) */
    @TableField("eer")
    private Double eer;

    /** 训练耗时(分钟) */
    @TableField("training_minutes")
    private Integer trainingMinutes;

    /** 是否为当前生产模型 */
    @TableField("is_active")
    private Boolean isActive;

    /** 模型文件路径 */
    @TableField("model_path")
    private String modelPath;

    /** 模型描述 */
    @TableField("description")
    private String description;

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
