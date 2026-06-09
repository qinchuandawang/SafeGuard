package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.*;
import com.sdu.safeguard.mapper.*;
import com.sdu.safeguard.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 管理后台 REST API 控制器（Vue SPA 使用）
 * 路径前缀 /api/admin，前端通过 baseURL=/api + /admin/xxx 访问
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminApiController {

    private final KnowledgeService knowledgeService;
    private final AudioTrainingService audioTrainingService;
    private final UserService userService;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final AudioDetectionRecordMapper audioRecordMapper;
    private final DetectionRecordMapper detectionRecordMapper;
    private final UserMapper userMapper;

    @GetMapping("/users")
    public Result<List<User>> getUsers() {
        try {
            // 全表查询加 LIMIT 兜底
            return Result.success(userMapper.findRecentUsers(1000));
        } catch (Exception e) {
            log.error("获取用户列表失败", e);
            return Result.error("获取失败");
        }
    }

    /**
     * 用户近 N 天每日新增数（默认 7 天）。前端 Dashboard / 用户页面活跃趋势图使用。
     */
    @GetMapping("/users/daily-stats")
    public Result<java.util.Map<String, Object>> getUserDailyStats(@RequestParam(defaultValue = "7") int days) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusDays(days);
            java.util.List<java.util.Map<String, Object>> rows = userMapper.countByDateGroup(since);

            java.util.Map<String, Integer> grouped = new java.util.LinkedHashMap<>();
            for (java.util.Map<String, Object> row : rows) {
                String date = row.get("date") == null ? "" : row.get("date").toString();
                int count = ((Number) row.get("count")).intValue();
                grouped.put(date, count);
            }

            java.util.List<String> dates = new java.util.ArrayList<>();
            java.util.List<Integer> counts = new java.util.ArrayList<>();
            java.time.LocalDate today = java.time.LocalDate.now();
            for (int i = days - 1; i >= 0; i--) {
                java.time.LocalDate d = today.minusDays(i);
                String key = d.toString();
                dates.add(key.substring(5)); // MM-dd
                counts.add(grouped.getOrDefault(key, 0));
            }

            result.put("dates", dates);
            result.put("counts", counts);
            result.put("days", days);
        } catch (Exception e) {
            log.error("获取用户日活统计失败", e);
            return Result.error("获取用户日活数据失败");
        }
        return Result.success(result);
    }

    @GetMapping("/stats/overview")
    public Result<Map<String, Object>> getOverviewStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            stats.put("userCount", userMapper.selectCount(null));
            stats.put("audioRecordCount", audioRecordMapper.selectCount(null));
            stats.put("detectionCount", detectionRecordMapper.selectCount(null));
            stats.put("knowledgeCount", knowledgeItemMapper.selectCount(null));
            stats.put("audioStats", audioTrainingService.getStatistics());
            stats.put("serviceAvailable", audioTrainingService.isServiceAvailable());

            stats.put("videoRecordCount", detectionRecordMapper.countByDetectionType("video"));
            stats.put("audioDetectionCount", detectionRecordMapper.countByDetectionType("audio"));
            stats.put("textDetectionCount", detectionRecordMapper.countByDetectionType("text"));
            stats.put("multiDetectionCount", detectionRecordMapper.countByDetectionType("multimodal"));
            stats.put("resultBreakdown", detectionRecordMapper.countGroupByResultWithPercentage());
        } catch (Exception e) {
            log.error("获取概览统计失败", e);
        }
        return Result.success(stats);
    }

    @GetMapping("/stats/trend")
    public Result<Map<String, Object>> getTrendStats(@RequestParam(defaultValue = "7") int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(days);
            List<Map<String, Object>> dailyTrend = detectionRecordMapper.countByDateAndType(since);
            List<Map<String, Object>> typeAvgRisk = detectionRecordMapper.avgRiskScoreByType();
            List<Map<String, Object>> typeResultCross = detectionRecordMapper.countGroupByTypeAndResult();

            result.put("dailyTrend", dailyTrend);
            result.put("typeAvgRisk", typeAvgRisk);
            result.put("typeResultCross", typeResultCross);
            result.put("days", days);
        } catch (Exception e) {
            log.error("获取趋势统计失败", e);
            return Result.error("获取趋势数据失败");
        }
        return Result.success(result);
    }

    @GetMapping("/stats/distribution")
    public Result<Map<String, Object>> getDistributionStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("byResult", detectionRecordMapper.countGroupByResultWithPercentage());
            result.put("byType", Map.of(
                    "audio", detectionRecordMapper.countByDetectionType("audio"),
                    "video", detectionRecordMapper.countByDetectionType("video"),
                    "text", detectionRecordMapper.countByDetectionType("text"),
                    "multimodal", detectionRecordMapper.countByDetectionType("multimodal")
            ));
            result.put("typeResultCross", detectionRecordMapper.countGroupByTypeAndResult());
        } catch (Exception e) {
            log.error("获取分布统计失败", e);
            return Result.error("获取分布数据失败");
        }
        return Result.success(result);
    }

    @GetMapping("/models")
    public Result<Map<String, Object>> getModels() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("models", audioTrainingService.getAllModels());
            result.put("activeModel", audioTrainingService.getActiveModel().orElse(null));
        } catch (Exception e) {
            log.error("获取模型列表失败", e);
            return Result.error("获取模型数据失败");
        }
        return Result.success(result);
    }

    @GetMapping("/stats/video")
    public Result<Map<String, Object>> getVideoStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            long total = detectionRecordMapper.countByDetectionType("video");
            List<Map<String, Object>> resultBreakdown = detectionRecordMapper.countGroupByTypeAndResult();
            long dangerous = 0, suspicious = 0, safe = 0, failed = 0;
            for (Map<String, Object> r : resultBreakdown) {
                if ("video".equals(r.get("detection_type"))) {
                    String res = (String) r.get("result");
                    long count = ((Number) r.get("count")).longValue();
                    if ("dangerous".equals(res)) dangerous = count;
                    else if ("suspicious".equals(res)) suspicious = count;
                    else if ("safe".equals(res)) safe = count;
                    else if ("failed".equals(res)) failed = count;
                }
            }
            stats.put("totalRecords", total);
            stats.put("dangerousCount", dangerous);
            stats.put("suspiciousCount", suspicious);
            stats.put("safeCount", safe);
            stats.put("failedCount", failed);
            stats.put("serviceAvailable", false);

            List<DetectionRecord> recentRecords = detectionRecordMapper.findVideoRecords();
            stats.put("recentRecords", recentRecords.size() > 10 ? recentRecords.subList(0, 10) : recentRecords);
        } catch (Exception e) {
            log.error("获取视频检测统计失败", e);
            return Result.error("获取视频统计数据失败");
        }
        return Result.success(stats);
    }
}
