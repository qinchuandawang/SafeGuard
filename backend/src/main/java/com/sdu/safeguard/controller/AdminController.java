package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.*;
import com.sdu.safeguard.service.*;
import com.sdu.safeguard.mapper.*;
import com.sdu.safeguard.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password, Model model,
                        jakarta.servlet.http.HttpSession session) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            model.addAttribute("error", "用户名和密码不能为空");
            return "login";
        }
        // 1) 优先按用户名对应的 admin openid 查找
        String openid = "admin_" + username.trim();
        User admin = userMapper.findByOpenidAny(openid);
        // 2) 兼容旧逻辑：取第一个 admin 账号
        if (admin == null) {
            admin = userMapper.findAnyAdmin();
        }
        if (admin == null) {
            model.addAttribute("error", "管理员账号不存在，请先注册");
            return "login";
        }
        if (admin.getPasswordHash() == null || admin.getPasswordHash().isBlank()) {
            model.addAttribute("error", "管理员密码未初始化，请联系系统管理员");
            return "login";
        }
        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            model.addAttribute("error", "用户名或密码错误");
            return "login";
        }
        // 更新最后登录时间
        admin.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(admin);
        // 生成 token 写入 session
        String token = jwtUtil.generateToken(admin.getId(), admin.getOpenid(), admin.getRole());
        session.setAttribute("admin_token", token);
        session.setAttribute("admin_id", admin.getId());
        session.setAttribute("admin_name", admin.getNickname());
        return "redirect:/admin/dashboard";
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        // 默认值：catch 块中保持一致，确保模板渲染不因空指针报错
        long userCount = 0;
        long audioRecordCount = 0;
        long detectionCount = 0;
        long knowledgeCount = 0;
        long videoRecordCount = 0;
        long videoCompletedCount = 0;
        long videoFailedCount = 0;
        long audioDetectionCount = 0;
        long textDetectionCount = 0;
        long multiDetectionCount = 0;
        Map<String, Object> audioStats = new LinkedHashMap<>();
        List<Map<String, Object>> resultBreakdown = java.util.Collections.emptyList();
        try {
            userCount = userMapper.selectCount(null);
            audioRecordCount = audioRecordMapper.selectCount(null);
            detectionCount = detectionRecordMapper.selectCount(null);
            knowledgeCount = knowledgeItemMapper.selectCount(null);

            // Audio stats
            audioStats = audioTrainingService.getStatistics();

            // Video stats
            videoRecordCount = detectionRecordMapper.countByDetectionType("video");
            try {
                List<Map<String, Object>> videoResults = detectionRecordMapper.countGroupByTypeAndResult();
                for (Map<String, Object> r : videoResults) {
                    if ("video".equals(r.get("detection_type"))) {
                        String result = (String) r.get("result");
                        long count = ((Number) r.get("count")).longValue();
                        // 除 failed 外的所有结果都计为"完成"（与原业务语义保持一致：视频检测进入终态）
                        if (!"failed".equals(result)) {
                            videoCompletedCount += count;
                        } else {
                            videoFailedCount += count;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("视频统计查询失败", e);
            }

            // Detection breakdown
            resultBreakdown = detectionRecordMapper.countGroupByResultWithPercentage();

            // Count by type
            audioDetectionCount = detectionRecordMapper.countByDetectionType("audio");
            textDetectionCount = detectionRecordMapper.countByDetectionType("text");
            multiDetectionCount = detectionRecordMapper.countByDetectionType("multimodal");
        } catch (Exception e) {
            log.error("加载Dashboard失败", e);
        }

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
        return "dashboard";
    }

    @GetMapping("/users")
    public String users(Model model) {
        try {
            // 页面渲染用，最多 1000 条；全表查询加 LIMIT 兜底避免生产环境 OOM
            List<User> userList = userMapper.findRecentUsers(1000);
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
            // 全表查询加 LIMIT 兜底
            return Result.success(userMapper.findRecentUsers(1000));
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
     * 获取检测趋势数据（最近N天）—— 给 Dashboard 趋势图专用，按日期+结果分组
     */
    @GetMapping("/api/stats/trend")
    @ResponseBody
    public Result<Map<String, Object>> getTrendStats(@RequestParam(defaultValue = "7") int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(days);
            // 按日期+结果分组的明细（用于前端双柱图）
            List<Map<String, Object>> dailyByResult = detectionRecordMapper.countByDateAndResult(since);

            // 拆分为：safe（安全）、suspicious+dangerous+failed（可疑）
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
                    // suspicious / dangerous / failed / unknown 一律归为"可疑"
                    bucket.merge("suspicious", count, Integer::sum);
                }
            }

            // 补齐缺失的日期，保证 X 轴连续
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
