package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.DetectionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 综合检测记录 Mapper
 */
@Mapper
public interface DetectionRecordMapper extends BaseMapper<DetectionRecord> {

    /**
     * 根据任务ID查询
     */
    @Select("SELECT * FROM detection_record WHERE task_id = #{taskId} AND deleted = 0")
    DetectionRecord findByTaskId(@Param("taskId") String taskId);

    /**
     * 根据用户ID查询检测记录（按时间倒序）
     */
    @Select("SELECT * FROM detection_record WHERE user_id = #{userId} AND deleted = 0 ORDER BY created_at DESC")
    List<DetectionRecord> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    /**
     * 根据检测类型查询
     */
    @Select("SELECT * FROM detection_record WHERE detection_type = #{detectionType} AND deleted = 0 ORDER BY created_at DESC")
    List<DetectionRecord> findByDetectionType(@Param("detectionType") String detectionType);

    /**
     * 查询时间范围内的检测记录
     */
    @Select("SELECT * FROM detection_record WHERE created_at BETWEEN #{start} AND #{end} AND deleted = 0 ORDER BY created_at DESC")
    List<DetectionRecord> findByCreatedAtBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 根据检测类型和时间范围统计
     */
    @Select("SELECT COUNT(*) FROM detection_record WHERE detection_type = #{detectionType} AND created_at BETWEEN #{start} AND #{end} AND deleted = 0")
    long countByDetectionTypeAndDateRange(
            @Param("detectionType") String detectionType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 根据检测类型统计数量
     */
    @Select("SELECT COUNT(*) FROM detection_record WHERE detection_type = #{detectionType} AND deleted = 0")
    long countByDetectionType(@Param("detectionType") String detectionType);

    /**
     * 按检测结果统计数量 (safe/suspicious/dangerous)
     */
    @Select("SELECT result, COUNT(*) AS count FROM detection_record WHERE result IS NOT NULL AND deleted = 0 GROUP BY result")
    List<Map<String, Object>> countGroupByResult();

    /**
     * 按日期统计每日检测量（最近N天）
     */
    @Select("SELECT DATE(created_at) AS date, COUNT(*) AS count FROM detection_record WHERE created_at >= #{since} AND deleted = 0 GROUP BY DATE(created_at) ORDER BY date ASC")
    List<Map<String, Object>> countByDateGroup(@Param("since") LocalDateTime since);

    /**
     * 按日期和检测类型统计每日检测量（最近N天）
     */
    @Select("SELECT DATE(created_at) AS date, detection_type, COUNT(*) AS count FROM detection_record WHERE created_at >= #{since} AND deleted = 0 GROUP BY DATE(created_at), detection_type ORDER BY date ASC")
    List<Map<String, Object>> countByDateAndType(@Param("since") LocalDateTime since);

    /**
     * 按检测类型统计平均风险分数
     */
    @Select("SELECT detection_type, AVG(risk_score) AS avg_risk, COUNT(*) AS count FROM detection_record WHERE risk_score IS NOT NULL AND deleted = 0 GROUP BY detection_type")
    List<Map<String, Object>> avgRiskScoreByType();

    /**
     * 按检测类型和结果交叉统计
     */
    @Select("SELECT detection_type, result, COUNT(*) AS count FROM detection_record WHERE deleted = 0 GROUP BY detection_type, result")
    List<Map<String, Object>> countGroupByTypeAndResult();

    /**
     * 统计所有记录中 safe/suspicious/dangerous 各自占比
     */
    @Select("SELECT COALESCE(result, 'unknown') AS result, COUNT(*) AS count, ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM detection_record WHERE deleted = 0), 1) AS percentage FROM detection_record WHERE deleted = 0 GROUP BY result ORDER BY count DESC")
    List<Map<String, Object>> countGroupByResultWithPercentage();

    /**
     * 获取视频检测记录（按时间倒序）
     */
    @Select("SELECT * FROM detection_record WHERE detection_type = 'video' AND deleted = 0 ORDER BY created_at DESC")
    List<DetectionRecord> findVideoRecords();

    /**
     * 最近N条检测记录
     */
    @Select("SELECT * FROM detection_record WHERE deleted = 0 ORDER BY created_at DESC LIMIT #{limit}")
    List<DetectionRecord> findRecentRecords(@Param("limit") int limit);
}
