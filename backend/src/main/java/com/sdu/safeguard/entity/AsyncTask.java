package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("async_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private String taskId;

    @TableField("type")
    private String type;

    @TableField("user_id")
    private Long userId;

    @TableField("status")
    private String status;

    @TableField("progress")
    private Integer progress;

    @TableField("result_json")
    private String resultJson;

    @TableField("error_message")
    private String errorMessage;

    @TableField("file_path")
    private String filePath;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
