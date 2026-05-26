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
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/detection")
@RequiredArgsConstructor
public class DetectionController {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/safe_guard/";

    private final DetectionService detectionService;
    private final LLMService llmService;
    private final DetectionTaskManager taskManager;

    @PostMapping("/audio")
    public Result<?> detectAudio(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("音频文件不能为空");
        }
        String filePath = null;
        try {
            filePath = saveToTemp(file);
            AudioDetectionResult result = detectionService.detectAudio(filePath);
            return Result.success(result);
        } catch (IOException e) {
            log.error("音频文件保存失败", e);
            return Result.error("文件处理失败");
        } finally {
            deleteQuietly(filePath);
        }
    }

    @PostMapping("/video")
    public Result<?> detectVideo(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("视频文件不能为空");
        }
        String filePath;
        try {
            filePath = saveToTemp(file);
        } catch (IOException e) {
            log.error("视频文件保存失败", e);
            return Result.error("文件处理失败");
        }
        DetectionTask task = taskManager.createTask("video");
        taskManager.runAsync(task.getTaskId(), taskId -> {
            try {
                VideoDetectionResult result = detectionService.detectVideo(filePath,
                        (pct, detail) -> taskManager.updateProgress(taskId, pct, detail));
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
        return Result.success(Map.of(
                "taskId", task.getTaskId(),
                "status", task.getStatus(),
                "progress", task.getProgress(),
                "result", task.getResult(),
                "error", task.getError()
        ));
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

    private static void deleteQuietly(String filePath) {
        if (filePath == null) return;
        File f = new File(filePath);
        if (f.exists() && !f.delete()) {
            log.debug("临时文件删除失败（可能被占用）: {}", filePath);
        }
    }
}
