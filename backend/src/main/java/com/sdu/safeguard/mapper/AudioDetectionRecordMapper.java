package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.AudioDetectionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 音频检测记录 Mapper
 */
@Mapper
public interface AudioDetectionRecordMapper extends BaseMapper<AudioDetectionRecord> {

    /**
     * 根据任务ID查询
     */
    @Select("SELECT * FROM audio_detection_record WHERE task_id = #{taskId} AND deleted = 0")
    AudioDetectionRecord findByTaskId(@Param("taskId") String taskId);

    /**
     * 根据用户ID查询检测记录（按时间倒序）
     */
    @Select("SELECT * FROM audio_detection_record WHERE user_id = #{userId} AND deleted = 0 ORDER BY created_at DESC")
    List<AudioDetectionRecord> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    /**
     * 根据检测结果统计数量
     */
    @Select("SELECT COUNT(*) FROM audio_detection_record WHERE detection_result = #{detectionResult} AND deleted = 0")
    long countByDetectionResult(@Param("detectionResult") String detectionResult);

    /**
     * 根据状态统计数量
     */
    @Select("SELECT COUNT(*) FROM audio_detection_record WHERE status = #{status} AND deleted = 0")
    long countByStatus(@Param("status") String status);

    /**
     * 查询时间范围内的检测记录
     */
    @Select("SELECT * FROM audio_detection_record WHERE created_at BETWEEN #{start} AND #{end} AND deleted = 0 ORDER BY created_at DESC")
    List<AudioDetectionRecord> findByCreatedAtBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 最近 N 条音频检测记录。全表查询的兜底版本，避免生产环境 OOM。
     */
    @Select("SELECT * FROM audio_detection_record WHERE deleted = 0 ORDER BY created_at DESC LIMIT #{limit}")
    List<AudioDetectionRecord> findRecent(@Param("limit") int limit);
}
