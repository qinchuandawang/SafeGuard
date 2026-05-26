package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.AudioDetectionRecord;
import com.sdu.safeguard.entity.AudioModel;
import com.sdu.safeguard.service.AudioTrainingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor
@Slf4j
public class AudioTrainingController {

    private final AudioTrainingService audioTrainingService;

    /**
     * 检查音频检测服务状态
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        Map<String, Object> status = Map.of(
                "serviceAvailable", audioTrainingService.isServiceAvailable(),
                "message", audioTrainingService.isServiceAvailable() 
                        ? "音频检测服务正常运行" 
                        : "音频检测服务不可用，请确保 audio-training/app.py 已启动"
        );
        return Result.success(status);
    }

    /**
     * 音频伪造检测
     */
    @PostMapping("/detect")
    public Result<AudioDetectionRecord> detectAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) Long userId) {
        
        if (file.isEmpty()) {
            return Result.error("音频文件不能为空");
        }

        String filename = file.getOriginalFilename();
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (!lower.endsWith(".wav") && !lower.endsWith(".flac") 
                    && !lower.endsWith(".mp3") && !lower.endsWith(".m4a") 
                    && !lower.endsWith(".ogg")) {
                return Result.error("不支持的音频格式，仅支持: wav, flac, mp3, m4a, ogg");
            }
        }

        try {
            log.info("收到音频检测请求: filename={}, size={}", filename, file.getSize());
            AudioDetectionRecord record = audioTrainingService.detectAudio(file, userId);
            return Result.success(record);
        } catch (Exception e) {
            log.error("音频检测失败", e);
            return Result.error("音频检测失败: " + e.getMessage());
        }
    }

    /**
     * 根据任务ID查询检测结果
     */
    @GetMapping("/record/{taskId}")
    public Result<AudioDetectionRecord> getRecord(@PathVariable String taskId) {
        return audioTrainingService.getRecordByTaskId(taskId)
                .map(Result::success)
                .orElse(Result.error("检测记录不存在: " + taskId));
    }

    /**
     * 获取检测记录列表
     */
    @GetMapping("/records")
    public Result<List<AudioDetectionRecord>> getRecords(
            @RequestParam(value = "userId", required = false) Long userId) {
        List<AudioDetectionRecord> records;
        if (userId != null) {
            records = audioTrainingService.getUserRecords(userId);
        } else {
            records = audioTrainingService.getAllRecords();
        }
        return Result.success(records);
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/statistics")
    public Result<Map<String, Object>> getStatistics() {
        return Result.success(audioTrainingService.getStatistics());
    }

    /**
     * 获取当前活跃模型
     */
    @GetMapping("/model/active")
    public Result<AudioModel> getActiveModel() {
        return audioTrainingService.getActiveModel()
                .map(Result::success)
                .orElse(Result.error("暂无活跃模型"));
    }

    /**
     * 获取所有模型
     */
    @GetMapping("/models")
    public Result<List<AudioModel>> getAllModels() {
        return Result.success(audioTrainingService.getAllModels());
    }

    /**
     * 注册/保存模型信息
     */
    @PostMapping("/model")
    public Result<AudioModel> saveModel(@RequestBody AudioModel model) {
        try {
            AudioModel saved = audioTrainingService.saveModel(model);
            return Result.success(saved);
        } catch (Exception e) {
            log.error("保存模型失败", e);
            return Result.error("保存模型失败: " + e.getMessage());
        }
    }
}
