package com.sdu.safeguard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库表
 */
@TableName("knowledge_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 问题/关键词 */
    @TableField("question")
    private String question;

    /** 答案/回复内容 */
    @TableField("answer")
    private String answer;

    /** 分类 */
    @TableField("category")
    private String category;

    /** 标签 */
    @TableField("tags")
    private String tags;

    /** 优先级 */
    @TableField("priority")
    private Integer priority;

    /** 是否启用 */
    @TableField("enabled")
    private Boolean enabled;

    /** 逻辑删除标记 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
