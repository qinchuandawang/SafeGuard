package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.AudioModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 音频模型 Mapper
 */
@Mapper
public interface AudioModelMapper extends BaseMapper<AudioModel> {

    /**
     * 获取当前激活的模型
     */
    @Select("SELECT * FROM audio_model WHERE is_active = true AND deleted = 0 LIMIT 1")
    AudioModel findActiveModel();

    /**
     * 根据模型类型查询
     */
    @Select("SELECT * FROM audio_model WHERE model_type = #{modelType} AND deleted = 0 ORDER BY created_at DESC")
    List<AudioModel> findByModelType(@Param("modelType") String modelType);

    /**
     * 获取所有激活的模型
     */
    @Select("SELECT * FROM audio_model WHERE is_active = true AND deleted = 0 ORDER BY created_at DESC")
    List<AudioModel> findAllActive();

    /**
     * 最近 N 条模型（按 id 倒序）。全表查询的兜底版本。
     */
    @Select("SELECT * FROM audio_model WHERE deleted = 0 ORDER BY id DESC LIMIT #{limit}")
    List<AudioModel> findRecent(@Param("limit") int limit);
}
