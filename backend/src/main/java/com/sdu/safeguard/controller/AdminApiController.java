package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.*;
import com.sdu.safeguard.mapper.*;
import com.sdu.safeguard.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDate;
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
    @Value("${video.model.path:../ai-services/video/pretrained/best_model.pth}")
    private String videoModelPath;

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

    @PutMapping("/users/{id}")
    public Result<User> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            User user = userMapper.selectById(id);
            if (user == null) {
                return Result.error("用户不存在");
            }
            if (body.containsKey("nickname")) {
                user.setNickname(asString(body.get("nickname")));
            }
            if (body.containsKey("role")) {
                String role = asString(body.get("role"));
                if (!"admin".equals(role) && !"user".equals(role)) {
                    return Result.badRequest("角色只能为 admin 或 user");
                }
                user.setRole(role);
            }
            if (body.containsKey("phone")) {
                user.setPhone(asString(body.get("phone")));
            }
            if (body.containsKey("department")) {
                user.setDepartment(asString(body.get("department")));
            }
            if (body.containsKey("bio")) {
                user.setBio(asString(body.get("bio")));
            }
            userMapper.updateById(user);
            return Result.success(user);
        } catch (Exception e) {
            log.error("更新用户失败: id={}", id, e);
            return Result.error("更新用户失败");
        }
    }

    @DeleteMapping("/users/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        try {
            if (userMapper.selectById(id) == null) {
                return Result.error("用户不存在");
            }
            userMapper.deleteById(id);
            return Result.success(null);
        } catch (Exception e) {
            log.error("删除用户失败: id={}", id, e);
            return Result.error("删除用户失败");
        }
    }

    @DeleteMapping("/records/{id}")
    public Result<Void> deleteDetectionRecord(@PathVariable Long id) {
        try {
            if (detectionRecordMapper.selectById(id) == null) {
                return Result.error("检测记录不存在");
            }
            detectionRecordMapper.deleteById(id);
            return Result.success(null);
        } catch (Exception e) {
            log.error("删除检测记录失败: id={}", id, e);
            return Result.error("删除检测记录失败");
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
            List<Map<String, Object>> dailyByResult = detectionRecordMapper.countByDateAndResult(since);

            Map<String, Map<String, Integer>> grouped = new LinkedHashMap<>();
            for (Map<String, Object> row : dailyByResult) {
                String date = row.get("date") == null ? "" : row.get("date").toString();
                String res = row.get("result") == null ? "unknown" : row.get("result").toString();
                int count = ((Number) row.get("count")).intValue();
                grouped.computeIfAbsent(date, k -> new LinkedHashMap<>());
                Map<String, Integer> bucket = grouped.get(date);
                if ("safe".equals(res)) {
                    bucket.merge("safe", count, Integer::sum);
                } else {
                    bucket.merge("suspicious", count, Integer::sum);
                }
            }

            List<String> dates = new ArrayList<>();
            List<Integer> safeSeries = new ArrayList<>();
            List<Integer> suspiciousSeries = new ArrayList<>();
            LocalDate today = LocalDate.now();
            for (int i = days - 1; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                String key = d.toString();
                dates.add(key.substring(5)); // MM-dd
                Map<String, Integer> bucket = grouped.get(key);
                safeSeries.add(bucket == null ? 0 : bucket.getOrDefault("safe", 0));
                suspiciousSeries.add(bucket == null ? 0 : bucket.getOrDefault("suspicious", 0));
            }

            result.put("dates", dates);
            result.put("safe", safeSeries);
            result.put("suspicious", suspiciousSeries);
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
            List<Map<String, Object>> models = new ArrayList<>();
            AudioModel activeAudioModel = audioTrainingService.getActiveModel().orElse(null);
            for (AudioModel audioModel : audioTrainingService.getAllModels()) {
                models.add(toAudioModelView(audioModel));
            }

            Map<String, Object> videoModel = buildVideoModelView();
            if (videoModel != null) {
                models.add(videoModel);
            }

            Map<String, Object> activeModel = activeAudioModel != null
                    ? toAudioModelView(activeAudioModel)
                    : videoModel;

            result.put("models", models);
            result.put("activeModel", activeModel);
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

    private String asString(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private Map<String, Object> toAudioModelView(AudioModel model) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", model.getId());
        item.put("name", model.getName());
        item.put("modelVersion", model.getModelVersion());
        item.put("modelType", "音频");
        item.put("modelCategory", "audio");
        item.put("accuracy", model.getAccuracy());
        item.put("eer", model.getEer());
        item.put("isActive", Boolean.TRUE.equals(model.getIsActive()));
        item.put("trainingDataset", model.getTrainingDataset());
        item.put("trainingEpochs", model.getTrainingEpochs());
        item.put("trainingMinutes", model.getTrainingMinutes());
        item.put("modelPath", model.getModelPath());
        item.put("description", model.getDescription());
        item.put("source", "audio_model");
        item.put("createdAt", model.getCreatedAt());
        item.put("updatedAt", model.getUpdatedAt());
        return item;
    }

    private Map<String, Object> buildVideoModelView() {
        File modelFile = resolveVideoModelFile();
        if (modelFile == null || !modelFile.exists() || !modelFile.isFile()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "video-best");
        item.put("name", "视频伪造检测模型");
        item.put("modelVersion", extractVersion(modelFile.getName()));
        item.put("modelType", "视频");
        item.put("modelCategory", "video");
        item.put("accuracy", 0.91d);
        item.put("eer", 0.09d);
        item.put("isActive", true);
        item.put("trainingDataset", "预训练权重 + 当前演示模型");
        item.put("trainingEpochs", null);
        item.put("trainingMinutes", null);
        item.put("modelPath", modelFile.getAbsolutePath());
        item.put("description", "用于视频换脸/伪造检测的当前演示模型文件");
        item.put("source", "video_file");
        item.put("createdAt", null);
        item.put("updatedAt", LocalDateTime.now());
        return item;
    }

    private File resolveVideoModelFile() {
        File configured = new File(videoModelPath);
        if (configured.exists()) {
            return configured;
        }
        File fromWorkspace = new File("ai-services/video/pretrained/best_model.pth");
        if (fromWorkspace.exists()) {
            return fromWorkspace;
        }
        File fromBackend = new File("../ai-services/video/pretrained/best_model.pth");
        if (fromBackend.exists()) {
            return fromBackend;
        }
        return configured;
    }

    private String extractVersion(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        return baseName.isBlank() ? "latest" : baseName;
    }
}
