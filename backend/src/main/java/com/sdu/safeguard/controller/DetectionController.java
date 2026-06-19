package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.dto.DetectionTask;
import com.sdu.safeguard.dto.MultiModalRequest;
import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.service.DetectionService;
import com.sdu.safeguard.service.DetectionTaskManager;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.util.InputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/detection")
@RequiredArgsConstructor
public class DetectionController {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/safe_guard/";
    private static final long MAX_AUDIO_SIZE = 20L * 1024 * 1024;
    private static final long MAX_VIDEO_SIZE = 100L * 1024 * 1024;
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(".wav", ".flac", ".mp3", ".m4a", ".ogg");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".avi", ".mkv", ".webm");

    private final DetectionService detectionService;
    private final LLMService llmService;
    private final DetectionTaskManager taskManager;

    @PostMapping("/audio")
    public Result<?> detectAudio(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("音频文件不能为空");
        }
        String validationError = validateMediaFile(file, "audio");
        if (validationError != null) {
            return Result.badRequest(validationError);
        }
        try {
            // 直接上传 MultipartFile 到检测服务，避免跨容器文件路径问题
            AudioDetectionResult result = detectionService.detectAudioWithMultipart(file);
            // 调用 LLM 生成详细分析报告（Key 未配置时返回规则化降级报告，非空）
            if (result != null) {
                try {
                    result.setReport(llmService.generateAudioReport(result));
                } catch (Exception e) {
                    log.warn("生成音频分析报告失败，使用降级报告: {}", e.getMessage());
                    result.setReport("AI 报告生成失败，请查看上方模型检测数据。");
                }
            }
            return Result.success(result);
        } catch (Exception e) {
            log.error("音频检测失败: {}", e.getMessage(), e);
            return Result.error("音频检测失败：" + e.getMessage());
        }
    }

    @PostMapping("/video")
    public Result<?> detectVideo(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.badRequest("视频文件不能为空");
        }
        String validationError = validateMediaFile(file, "video");
        if (validationError != null) {
            return Result.badRequest(validationError);
        }
        String filePath;
        try {
            filePath = saveToTemp(file);
        } catch (IOException e) {
            log.error("视频文件保存失败", e);
            return Result.error("文件处理失败");
        }
        DetectionTask task;
        try {
            task = taskManager.createTask("video");
        } catch (RuntimeException e) {
            // DB 故障等：createTask 抛异常时 filePath 已保存，lambda 还没启动，必须在外层清理
            log.error("创建视频检测任务失败，清理临时文件", e);
            deleteQuietly(filePath);
            return Result.error("任务创建失败：" + e.getMessage());
        }
        taskManager.runAsync(task.getTaskId(), taskId -> {
            try {
                VideoDetectionResult result = detectionService.detectVideo(filePath,
                        (pct, detail) -> taskManager.updateProgress(taskId, pct, detail));
                // 调用 LLM 生成详细分析报告（Key 未配置时返回规则化降级报告，非空）
                if (result != null) {
                    try {
                        result.setReport(llmService.generateVideoReport(result));
                    } catch (Exception e) {
                        log.warn("生成视频分析报告失败，使用降级报告: {}", e.getMessage());
                        result.setReport("AI 报告生成失败，请查看上方模型检测数据。");
                    }
                }
                taskManager.complete(taskId, result);
            } finally {
                deleteQuietly(filePath);
            }
        });
        return Result.success(Map.of("taskId", task.getTaskId(), "status", "processing"));
    }

    @PostMapping("/text")
    public Result<String> detectText(@RequestBody Map<String, String> request) {
        if (request == null) {
            return Result.error("请求体不能为空");
        }
        String text = request.get("text");
        String validationError = InputValidator.validateAnalysisText(text);
        if (validationError != null) {
            return Result.error(validationError);
        }
        try {
            return Result.success(llmService.analyzeText(text));
        } catch (Exception e) {
            log.error("文本检测异常: type={}, msg={}", e.getClass().getSimpleName(), e.getMessage(), e);
            return Result.error("检测失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @PostMapping("/multi")
    public Result<String> detectMulti(@RequestBody MultiModalRequest request) {
        if (request == null) {
            return Result.error("请求体不能为空");
        }
        try {
            return Result.success(llmService.analyzeMultiModal(
                    request.getText(),
                    request.getAudioResult(),
                    request.getVideoResult()
            ));
        } catch (Exception e) {
            log.error("综合检测异常: type={}, msg={}", e.getClass().getSimpleName(), e.getMessage(), e);
            return Result.error("综合检测失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @GetMapping("/task/{taskId}")
    public Result<Map<String, Object>> getTask(@PathVariable String taskId) {
        DetectionTask task = taskManager.getTask(taskId);
        if (task == null) {
            return Result.error("任务不存在");
        }
        Map<String, Object> resultMap = new java.util.LinkedHashMap<>();
        resultMap.put("taskId", task.getTaskId());
        resultMap.put("status", task.getStatus());
        resultMap.put("progress", task.getProgress());
        resultMap.put("result", task.getResult());
        resultMap.put("error", task.getError());
        return Result.success(resultMap);
    }

    @GetMapping(value = "/task/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@PathVariable String taskId) {
        DetectionTask task = taskManager.getTask(taskId);
        if (task == null || task.getSseEmitter() == null) {
            SseEmitter gone = new SseEmitter(0L);
            gone.complete();
            return gone;
        }
        return task.getSseEmitter();
    }

    private String saveToTemp(MultipartFile file) throws IOException {
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + tempDir.getAbsolutePath());
        }
        String originalName = file.getOriginalFilename();
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf('.'));
        }
        String fileName = UUID.randomUUID() + extension;
        File destFile = new File(tempDir, fileName);
        file.transferTo(destFile);
        return destFile.getAbsolutePath();
    }

    private String validateMediaFile(MultipartFile file, String type) {
        String extension = extractExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if ("audio".equals(type)) {
            if (file.getSize() > MAX_AUDIO_SIZE) {
                return "音频文件不能超过20MB";
            }
            if (!AUDIO_EXTENSIONS.contains(extension)) {
                return "不支持的音频格式";
            }
        }
        if ("video".equals(type)) {
            if (file.getSize() > MAX_VIDEO_SIZE) {
                return "视频文件不能超过100MB";
            }
            if (!VIDEO_EXTENSIONS.contains(extension)) {
                return "不支持的视频格式";
            }
        }
        return null;
    }

    private String extractExtension(String originalName) {
        if (originalName == null || !originalName.contains(".")) {
            return "";
        }
        return originalName.substring(originalName.lastIndexOf('.'));
    }

    private static void deleteQuietly(String filePath) {
        if (filePath == null) return;
        File f = new File(filePath);
        if (f.exists() && !f.delete()) {
            log.debug("临时文件删除失败（可能被占用）: {}", filePath);
        }
    }
}
