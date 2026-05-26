package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.DetectionRecord;
import com.sdu.safeguard.mapper.DetectionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class DetectionRecordController {

    private final DetectionRecordMapper detectionRecordMapper;

    @PostMapping("/save")
    public Result<DetectionRecord> saveRecord(@RequestBody DetectionRecord record) {
        if (record.getTaskId() == null) {
            return Result.error("任务ID不能为空");
        }
        try {
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
            @RequestParam(required = false) Integer limit) {
        try {
            List<DetectionRecord> records;
            if (userId != null) {
                records = detectionRecordMapper.findByUserIdOrderByCreatedAtDesc(userId);
            } else {
                records = detectionRecordMapper.selectList(null);
            }
            if (limit != null && limit > 0 && records.size() > limit) {
                records = records.subList(0, limit);
            }
            return Result.success(records);
        } catch (Exception e) {
            log.error("查询检测记录失败", e);
            return Result.error("查询失败");
        }
    }

    @GetMapping("/stats/daily")
    public Result<Map<String, Object>> getDailyStats() {
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
}
