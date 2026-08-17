package com.sdu.safeguard.service;

import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Slf4j
@Service
public class DetectionRecoveryService {

    private final AsyncTaskMapper asyncTaskMapper;
    private final DetectionTaskManager taskManager;
    private final DetectionService detectionService;
    private final LLMService llmService;
    private final ObjectStorageService objectStorageService;
    private final DetectionWorkQueueService workQueueService;

    @Autowired
    public DetectionRecoveryService(AsyncTaskMapper asyncTaskMapper,
                                    DetectionTaskManager taskManager,
                                    DetectionService detectionService,
                                    LLMService llmService,
                                    ObjectStorageService objectStorageService,
                                    DetectionWorkQueueService workQueueService) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.taskManager = taskManager;
        this.detectionService = detectionService;
        this.llmService = llmService;
        this.objectStorageService = objectStorageService;
        this.workQueueService = workQueueService;
    }

    /** 保留旧构造器，兼容恢复服务的既有单元测试。 */
    public DetectionRecoveryService(AsyncTaskMapper asyncTaskMapper,
                                    DetectionTaskManager taskManager,
                                    DetectionService detectionService,
                                    LLMService llmService,
                                    ObjectStorageService objectStorageService) {
        this(asyncTaskMapper, taskManager, detectionService, llmService,
                objectStorageService, null);
    }

    @Value("${task.recovery.enabled:true}")
    private boolean enabled;

    @Value("${task.recovery.queued-timeout-seconds:60}")
    private long queuedTimeoutSeconds;

    @Value("${task.recovery.processing-timeout-seconds:900}")
    private long processingTimeoutSeconds;

    @Value("${task.recovery.batch-size:20}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${task.recovery.fixed-delay-ms:30000}")
    public void recoverStaleTasks() {
        if (!enabled) return;
        LocalDateTime queuedBefore = LocalDateTime.now().minusSeconds(queuedTimeoutSeconds);
        LocalDateTime processingBefore = LocalDateTime.now().minusSeconds(processingTimeoutSeconds);
        try {
            for (AsyncTask task : asyncTaskMapper.findRecoverable(queuedBefore, processingBefore, batchSize)) {
                LocalDateTime staleBefore = "processing".equals(task.getStatus())
                        ? processingBefore : queuedBefore;
                if (asyncTaskMapper.claimForRecovery(
                        task.getTaskId(), task.getStatus(), staleBefore) != 1) {
                    continue;
                }
                taskManager.prepareRecoveredTask(task);
                try {
                    if (workQueueService == null) {
                        // 兼容旧测试构造器；生产环境始终走 RocketMQ 工作队列。
                        taskManager.runAsync(task.getTaskId(), ignored -> executeRecoveredVideo(task));
                    } else {
                        workQueueService.enqueueInference(task.getTaskId());
                    }
                } catch (TaskQueueFullException queueFullException) {
                    log.info("恢复任务暂未重新入队，等待下一轮容量释放: taskId={}", task.getTaskId());
                }
                log.warn("重新调度中断的检测任务: taskId={}, previousStatus={}",
                        task.getTaskId(), task.getStatus());
            }
        } catch (Exception e) {
            log.warn("扫描可恢复检测任务失败: {}", e.getMessage());
        }
    }

    private void executeRecoveredVideo(AsyncTask task) {
        Path mediaPath = resolveMediaPath(task);
        boolean temporaryDownload = task.getObjectKey() != null && !task.getObjectKey().isBlank();
        try {
            taskManager.updateProgress(task.getTaskId(), 5, java.util.Map.of("message", "正在恢复中断任务..."));
            VideoDetectionResult result = detectionService.detectVideo(mediaPath.toString(), task.getTaskId(),
                    (progress, detail) -> taskManager.updateProgress(task.getTaskId(), progress, detail));
            if (result != null) result.setReport(llmService.generateVideoReport(result));
            taskManager.complete(task.getTaskId(), result);
        } finally {
            if (temporaryDownload) {
                try {
                    Files.deleteIfExists(mediaPath);
                } catch (Exception ignored) {
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
