package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.DetectionRecord;
import com.sdu.safeguard.mapper.DetectionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class DetectionRecordController {

    private final DetectionRecordMapper detectionRecordMapper;

    @PostMapping("/save")
    public Result<DetectionRecord> saveRecord(@RequestBody DetectionRecord record, HttpServletRequest request) {
        if (record.getTaskId() == null) {
            return Result.error("任务ID不能为空");
        }
        try {
            Long currentUserId = currentUserId(request);
            if (currentUserId != null && !isAdmin(request)) {
                record.setUserId(currentUserId);
            }
            if (record.getCreatedAt() == null) {
                record.setCreatedAt(java.time.LocalDateTime.now());
            }
            detectionRecordMapper.insert(record);
            log.info("检测记录已保存: taskId={}, type={}, result={}",
                    record.getTaskId(), record.getDetectionType(), record.getResult());
            return Result.success(record);
        } catch (Exception e) {
            log.error("保存检测记录失败", e);
            return Result.error("保存失败: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<List<DetectionRecord>> getRecords(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        try {
            int safeLimit = (limit == null || limit <= 0) ? 200 : Math.min(limit, 1000);
            Long queryUserId = userId;
            if (!isAdmin(request)) {
                queryUserId = currentUserId(request);
            }
            List<DetectionRecord> records;
            if (queryUserId != null) {
                records = detectionRecordMapper.findByUserIdOrderByCreatedAtDesc(queryUserId);
                if (records.size() > safeLimit) {
                    records = records.subList(0, safeLimit);
                }
            } else {
                // 全表查询加 LIMIT 兜底，避免生产环境 OOM/雪崩
                records = detectionRecordMapper.findRecentRecords(safeLimit);
            }
            return Result.success(records);
        } catch (Exception e) {
            log.error("查询检测记录失败", e);
            return Result.error("查询失败");
        }
    }

    public Result<List<DetectionRecord>> getRecords(Long userId, Integer limit) {
        return getRecords(userId, limit, null);
    }

    @GetMapping("/stats/daily")
    public Result<Map<String, Object>> getDailyStats(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.badRequest("需要管理员权限");
        }
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        try {
            long total = detectionRecordMapper.selectCount(null);
            long audioCount = detectionRecordMapper.countByDetectionType("audio");
            long videoCount = detectionRecordMapper.countByDetectionType("video");
            long textCount = detectionRecordMapper.countByDetectionType("text");

            stats.put("total", total);
            stats.put("audioCount", audioCount);
            stats.put("videoCount", videoCount);
            stats.put("textCount", textCount);
        } catch (Exception e) {
            log.error("获取日统计失败", e);
        }
        return Result.success(stats);
    }

    public Result<Map<String, Object>> getDailyStats() {
        return getDailyStats(null);
    }

    private Long currentUserId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object userId = request.getAttribute("currentUserId");
        return userId instanceof Long ? (Long) userId : null;
    }

    private boolean isAdmin(HttpServletRequest request) {
        if (request == null) {
            return true;
        }
        Object role = request.getAttribute("currentRole");
        return "admin".equals(role);
    }
}
