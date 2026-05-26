package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.*;
import com.sdu.safeguard.service.*;
import com.sdu.safeguard.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final KnowledgeService knowledgeService;
    private final AudioTrainingService audioTrainingService;
    private final UserService userService;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final AudioDetectionRecordMapper audioRecordMapper;
    private final DetectionRecordMapper detectionRecordMapper;
    private final UserMapper userMapper;

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password, Model model) {
        if ("admin".equals(username) && "admin123".equals(password)) {
            return "redirect:/admin/dashboard";
        }
        model.addAttribute("error", "用户名或密码错误");
        return "login";
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        try {
            long userCount = userMapper.selectCount(null);
            long audioRecordCount = audioRecordMapper.selectCount(null);
            long detectionCount = detectionRecordMapper.selectCount(null);
            long knowledgeCount = knowledgeItemMapper.selectCount(null);

            // Audio stats
            Map<String, Object> audioStats = audioTrainingService.getStatistics();

            // Video stats
            long videoRecordCount = detectionRecordMapper.countByDetectionType("video");
            long videoCompletedCount = 0;
            long videoFailedCount = 0;
            try {
                List<Map<String, Object>> videoResults = detectionRecordMapper.countGroupByTypeAndResult();
                for (Map<String, Object> r : videoResults) {
                    if ("video".equals(r.get("detection_type"))) {
                        String result = (String) r.get("result");
                        long count = ((Number) r.get("count")).longValue();
                        if ("dangerous".equals(result)) videoCompletedCount += count;
                        else if ("safe".equals(result)) videoCompletedCount += count;
                        else if ("suspicious".equals(result)) videoCompletedCount += count;
                        if ("failed".equals(result)) videoFailedCount += count;
                    }
                }
            } catch (Exception e) {
                log.warn("视频统计查询失败", e);
            }

            // Detection breakdown
            List<Map<String, Object>> resultBreakdown = detectionRecordMapper.countGroupByResultWithPercentage();

            // Count by type
            long audioDetectionCount = detectionRecordMapper.countByDetectionType("audio");
            long textDetectionCount = detectionRecordMapper.countByDetectionType("text");
            long multiDetectionCount = detectionRecordMapper.countByDetectionType("multimodal");

            model.addAttribute("userCount", userCount);
            model.addAttribute("audioRecordCount", audioRecordCount);
            model.addAttribute("detectionCount", detectionCount);
            model.addAttribute("knowledgeCount", knowledgeCount);
            model.addAttribute("audioStats", audioStats);
            model.addAttribute("serviceAvailable", audioTrainingService.isServiceAvailable());

            // Video stats
            model.addAttribute("videoRecordCount", videoRecordCount);
            model.addAttribute("videoCompletedCount", videoCompletedCount);
            model.addAttribute("videoFailedCount", videoFailedCount);

            // Breakdown by type
            model.addAttribute("audioDetectionCount", audioDetectionCount);
            model.addAttribute("videoDetectionCount", videoRecordCount);
            model.addAttribute("textDetectionCount", textDetectionCount);
            model.addAttribute("multiDetectionCount", multiDetectionCount);

            // Result breakdown
            model.addAttribute("resultBreakdown", resultBreakdown);
        } catch (Exception e) {
            log.error("加载Dashboard失败", e);
        }
        return "dashboard";
    }

    @GetMapping("/users")
    public String users(Model model) {
        try {
            List<User> userList = userMapper.selectList(null);
            model.addAttribute("users", userList);
        } catch (Exception e) {
            log.error("加载用户列表失败", e);
            model.addAttribute("users", Collections.emptyList());
        }
        return "users";
    }

    @GetMapping("/knowledge")
    public String knowledge(Model model) {
        try {
            List<KnowledgeItem> items = knowledgeService.getAll();
            model.addAttribute("items", items);
        } catch (Exception e) {
            log.error("加载知识库失败", e);
            model.addAttribute("items", Collections.emptyList());
        }
        return "knowledge";
    }

    @GetMapping("/records")
    public String records(Model model) {
        try {
            List<AudioDetectionRecord> audioRecords = audioTrainingService.getAllRecords();
            model.addAttribute("audioRecords", audioRecords);
        } catch (Exception e) {
            log.error("加载检测记录失败", e);
            model.addAttribute("audioRecords", Collections.emptyList());
        }
        return "records";
    }

    @GetMapping("/models")
    public String models(Model model) {
        try {
            List<AudioModel> modelList = audioTrainingService.getAllModels();
            model.addAttribute("models", modelList);
            model.addAttribute("activeModel", audioTrainingService.getActiveModel().orElse(null));
        } catch (Exception e) {
            log.error("加载模型列表失败", e);
            model.addAttribute("models", Collections.emptyList());
        }
        return "models";
    }

    @GetMapping("/api/users")
    @ResponseBody
    public Result<List<User>> getUsers() {
        try {
            return Result.success(userMapper.selectList(null));
        } catch (Exception e) {
            log.error("获取用户列表失败", e);
            return Result.error("获取失败");
        }
    }

    @GetMapping("/api/stats/overview")
    @ResponseBody
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

    /**
     * 获取检测趋势数据（最近N天）
     */
    @GetMapping("/api/stats/trend")
    @ResponseBody
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

    /**
     * 获取检测结果分布
     */
    @GetMapping("/api/stats/distribution")
    @ResponseBody
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

    /**
     * 获取视频检测统计
     */
    @GetMapping("/api/stats/video")
    @ResponseBody
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
            stats.put("serviceAvailable", false); // Will be updated when video service is running

            List<DetectionRecord> recentRecords = detectionRecordMapper.findVideoRecords();
            stats.put("recentRecords", recentRecords.size() > 10 ? recentRecords.subList(0, 10) : recentRecords);
        } catch (Exception e) {
            log.error("获取视频检测统计失败", e);
            return Result.error("获取视频统计数据失败");
        }
        return Result.success(stats);
    }
}
