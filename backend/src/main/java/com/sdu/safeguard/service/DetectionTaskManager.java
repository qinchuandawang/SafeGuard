package com.sdu.safeguard.service;

import tools.jackson.databind.ObjectMapper;
import com.sdu.safeguard.dto.DetectionTask;
import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
public class DetectionTaskManager {

    private final ConcurrentHashMap<String, DetectionTask> activeTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "task-cleanup");
                t.setDaemon(true);
                return t;
            });

    private final AsyncTaskMapper asyncTaskMapper;
    private final ObjectMapper objectMapper;

    private static final long SSE_TIMEOUT_MS = 600_000L;
    private static final long CLEANUP_DELAY_MS = 30_000L;

    public DetectionTaskManager(AsyncTaskMapper asyncTaskMapper, ObjectMapper objectMapper) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        markInterruptedTasks();
        log.info("异步任务管理器初始化完成");
    }

    private void markInterruptedTasks() {
        try {
            LocalDateTime before = LocalDateTime.now().minusMinutes(5);
            asyncTaskMapper.findStuckTasks(before).forEach(task -> {
                asyncTaskMapper.markInterrupted(task.getTaskId());
                log.info("标记中断任务: taskId={}", task.getTaskId());
            });
        } catch (Exception e) {
            log.warn("标记中断任务失败（数据库可能未初始化）: {}", e.getMessage());
        }
    }

    public DetectionTask createTask(String type) {
        return createTask(type, null);
    }

    public DetectionTask createTask(String type, Long userId) {
        String taskId = UUID.randomUUID().toString();
        DetectionTask task = new DetectionTask();
        task.setTaskId(taskId);
        task.setType(type);
        task.setStatus("processing");
        task.setProgress(0);
        task.setSseEmitter(new SseEmitter(SSE_TIMEOUT_MS));

        try {
            AsyncTask asyncTask = AsyncTask.builder()
                    .taskId(taskId)
                    .type(type)
                    .userId(userId)
                    .status("processing")
                    .progress(0)
                    .build();
            asyncTaskMapper.insert(asyncTask);
        } catch (Exception e) {
            log.warn("持久化任务失败（数据库未就绪）: {}", e.getMessage());
        }

        activeTasks.put(taskId, task);
        log.info("创建异步任务: taskId={}, type={}, userId={}", taskId, type, userId);
        return task;
    }

    public void updateProgress(String taskId, int progress, Object detail) {
        DetectionTask task = activeTasks.get(taskId);
        if (task == null) return;
        task.setProgress(progress);
        sendSse(task, "progress", detail);

        try {
            AsyncTask asyncTask = asyncTaskMapper.findByTaskId(taskId);
            if (asyncTask != null) {
                asyncTask.setProgress(progress);
                asyncTaskMapper.updateById(asyncTask);
            }
        } catch (Exception e) {
            log.warn("DB更新失败（任务进度）: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    public void complete(String taskId, Object result) {
        DetectionTask task = activeTasks.get(taskId);
        if (task == null) return;
        task.setStatus("completed");
        task.setProgress(100);
        task.setResult(result);
        sendSse(task, "completed", result);
        closeEmitter(task);
        log.info("异步任务完成: taskId={}", taskId);

        persistResult(taskId, "completed", result, null);
        scheduleCleanup(taskId);
    }

    public void fail(String taskId, String error) {
        DetectionTask task = activeTasks.get(taskId);
        if (task == null) return;
        task.setStatus("failed");
        task.setError(error);
        sendSse(task, "error", Map.of("error", error));
        closeEmitter(task);
        log.error("异步任务失败: taskId={}, error={}", taskId, error);

        persistResult(taskId, "failed", null, error);
        scheduleCleanup(taskId);
    }

    public DetectionTask getTask(String taskId) {
        DetectionTask task = activeTasks.get(taskId);
        if (task == null) {
            try {
                AsyncTask asyncTask = asyncTaskMapper.findByTaskId(taskId);
                if (asyncTask != null) {
                    task = new DetectionTask();
                    task.setTaskId(asyncTask.getTaskId());
                    task.setType(asyncTask.getType());
                    task.setStatus(asyncTask.getStatus());
                    task.setProgress(asyncTask.getProgress());
                    task.setError(asyncTask.getErrorMessage());
                    if (asyncTask.getResultJson() != null) {
                        try {
                            task.setResult(objectMapper.readValue(asyncTask.getResultJson(), Map.class));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        return task;
    }

    @Async("detectionTaskExecutor")
    public void runAsync(String taskId, RunnableWithTaskId runnable) {
        try {
            runnable.run(taskId);
        } catch (Exception e) {
            log.error("异步任务执行异常: taskId={}", taskId, e);
            fail(taskId, e.getMessage() != null ? e.getMessage() : "任务执行失败");
        }
    }

    private void sendSse(DetectionTask task, String event, Object data) {
        SseEmitter emitter = task.getSseEmitter();
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.debug("SSE推送失败（客户端可能已断开）: taskId={}", task.getTaskId());
            closeEmitter(task);
        }
    }

    private void closeEmitter(DetectionTask task) {
        SseEmitter emitter = task.getSseEmitter();
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {}
            task.setSseEmitter(null);
        }
    }

    private void persistResult(String taskId, String status, Object result, String error) {
        try {
            AsyncTask asyncTask = asyncTaskMapper.findByTaskId(taskId);
            if (asyncTask != null) {
                asyncTask.setStatus(status);
                asyncTask.setProgress(status.equals("completed") ? 100 : asyncTask.getProgress());
                if (result != null) {
                    asyncTask.setResultJson(objectMapper.writeValueAsString(result));
                }
                if (error != null) {
                    asyncTask.setErrorMessage(error);
                }
                asyncTask.setCompletedAt(status.equals("completed") || status.equals("failed")
                        ? LocalDateTime.now() : null);
                asyncTaskMapper.updateById(asyncTask);
            }
        } catch (Exception e) {
            log.warn("持久化任务结果失败: {}", e.getMessage());
        }
    }

    private void scheduleCleanup(String taskId) {
        cleanupScheduler.schedule(() -> {
            activeTasks.remove(taskId);
            log.debug("清理内存中的任务: taskId={}", taskId);
        }, CLEANUP_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @FunctionalInterface
    public interface RunnableWithTaskId {
        void run(String taskId) throws Exception;
    }
}
