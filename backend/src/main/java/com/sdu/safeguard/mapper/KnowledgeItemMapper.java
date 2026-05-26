package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.KnowledgeItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库 Mapper
 */
@Mapper
public interface KnowledgeItemMapper extends BaseMapper<KnowledgeItem> {

    /**
     * 根据分类查询
     */
    @Select("SELECT * FROM knowledge_item WHERE category = #{category} AND deleted = 0")
    List<KnowledgeItem> findByCategory(@Param("category") String category);

    /**
     * 根据关键词搜索（问题或答案）
     */
    @Select("SELECT * FROM knowledge_item WHERE (question LIKE CONCAT('%', #{keyword}, '%') OR answer LIKE CONCAT('%', #{keyword}, '%')) AND deleted = 0")
    List<KnowledgeItem> searchByKeyword(@Param("keyword") String keyword);

    /**
     * 获取已启用的知识项
     */
    @Select("SELECT * FROM knowledge_item WHERE enabled = true AND deleted = 0 ORDER BY priority DESC, id ASC")
    List<KnowledgeItem> findEnabled();
}
