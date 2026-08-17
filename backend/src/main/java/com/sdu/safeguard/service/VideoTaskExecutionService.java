package com.sdu.safeguard.service;

import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTaskExecutionService {

    private final AsyncTaskMapper asyncTaskMapper;
    private final DetectionTaskManager taskManager;
    private final DetectionService detectionService;
    private final LLMService llmService;
    private final ObjectStorageService objectStorageService;

    public void execute(AsyncTask task) {
        Path mediaPath = resolveMediaPath(task);
        boolean temporaryDownload = task.getObjectKey() != null && !task.getObjectKey().isBlank();
        try {
            taskManager.updateProgress(task.getTaskId(), 0, Map.of("message", "准备处理视频文件..."));
            VideoDetectionResult result = detectionService.detectVideo(mediaPath.toString(), task.getTaskId(),
                    (progress, detail) -> taskManager.updateProgress(task.getTaskId(), progress, detail));
            if (result != null) {
                taskManager.updateProgress(task.getTaskId(), 90,
                        Map.of("message", "正在生成 AI 分析报告..."));
                try {
                    result.setReport(llmService.generateVideoReport(result));
                } catch (Exception exception) {
                    log.warn("视频 AI 报告生成失败，使用降级报告: taskId={}, error={}",
                            task.getTaskId(), exception.getMessage());
                    result.setReport("AI 报告生成失败，请查看模型检测结果。");
                }
            }
            taskManager.complete(task.getTaskId(), result);
        } finally {
            if (temporaryDownload) {
                try {
                    Files.deleteIfExists(mediaPath);
                } catch (Exception exception) {
                    log.debug("删除视频临时文件失败: path={}, error={}", mediaPath, exception.getMessage());
                }
            }
        }
    }

    private Path resolveMediaPath(AsyncTask task) {
        if (task.getFilePath() != null && !task.getFilePath().isBlank()) {
            Path localPath = Path.of(task.getFilePath());
            if (Files.isRegularFile(localPath)) return localPath;
        }
        return objectStorageService.downloadToTemp(task.getObjectKey());
    }
}
