package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdu.safeguard.dto.DetectionTask;
import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
public class DetectionTaskManager {

    private final ConcurrentHashMap<String, DetectionTask> activeTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<DetectionTask>> taskCreationFutures =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> taskEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> taskRefreshTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "task-cleanup");
                t.setDaemon(true);
                return t;
            });

    private final AsyncTaskMapper asyncTaskMapper;
    private final ObjectMapper objectMapper;
    private final TaskEventService taskEventService;
    private final IdempotencyService idempotencyService;

    private static final long SSE_TIMEOUT_MS = 300_000L;
    private static final long CLEANUP_DELAY_MS = 30_000L;

    @Value("${task.idempotency.wait-time-ms:2000}")
    private long idempotencyWaitTimeMs = 2000L;

    public DetectionTaskManager(AsyncTaskMapper asyncTaskMapper, ObjectMapper objectMapper,
                         TaskEventService taskEventService, IdempotencyService idempotencyService) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.objectMapper = objectMapper;
        this.taskEventService = taskEventService;
        this.idempotencyService = idempotencyService;
    }

    @PostConstruct
    public void init() {
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

    @Deprecated(forRemoval = false)
    public DetectionTask createTask(String type) {
        DetectionTask task = createTask(type, null);
        // 保留旧同步调用契约；新异步入口必须使用 createTaskIdempotently 获取 queued 任务。
        if (asyncTaskMapper.markProcessing(task.getTaskId(), 0) == 1) {
            task.setStatus("processing");
        }
        return task;
    }

    public DetectionTask createTask(String type, Long userId) {
        return createTask(type, userId, null, null, null);
    }

    public DetectionTask createTask(String type, Long userId, String idempotencyKey,
                                    String fileHash, String modelId) {
        return createTaskIdempotently(type, userId, idempotencyKey, fileHash, modelId).task();
    }

    public TaskCreation createTaskIdempotently(String type, Long userId, String idempotencyKey,
                                               String fileHash, String modelId) {
        boolean idempotent = idempotencyKey != null && !idempotencyKey.isBlank();
        if (idempotent) {
            String existingTaskId = idempotencyService.findTaskId(idempotencyKey).orElse(null);
            if (existingTaskId != null) {
                DetectionTask existing = getTask(existingTaskId);
                if (existing != null) {
                    return new TaskCreation(existing, false);
                }
            }
        }

        CompletableFuture<DetectionTask> creationFuture = null;
        if (idempotent) {
            creationFuture = new CompletableFuture<>();
            CompletableFuture<DetectionTask> inFlight =
                    taskCreationFutures.putIfAbsent(idempotencyKey, creationFuture);
            if (inFlight != null) {
                return new TaskCreation(awaitCreation(inFlight), false);
            }
        }

        String taskId = UUID.randomUUID().toString();
        if (!idempotencyService.reserve(idempotencyKey, taskId)) {
            try {
                DetectionTask existing = awaitPersistedTask(idempotencyKey);
                completeCreation(creationFuture, existing, null);
                return new TaskCreation(existing, false);
            } catch (RuntimeException e) {
                completeCreation(creationFuture, null, e);
                throw e;
            } finally {
                removeCreationFuture(idempotencyKey, creationFuture);
            }
        }
        DetectionTask task = new DetectionTask();
        task.setTaskId(taskId);
        task.setType(type);
        task.setStatus("queued");
        task.setProgress(0);
        Map<String, Object> createdEvent = new LinkedHashMap<>();
        createdEvent.put("type", type);
        createdEvent.put("userId", userId);
        createdEvent.put("status", "queued");
        createdEvent.put("progress", 0);

        try {
            AsyncTask asyncTask = AsyncTask.builder()
                    .taskId(taskId)
                    .type(type)
                    .userId(userId)
                    .status("queued")
                    .progress(0)
                    .fileHash(fileHash)
                    .idempotencyKey(idempotencyKey)
                    .modelId(modelId)
                    .storageTier("hot")
                    .version(0)
                    .build();
            taskEventService.execute(taskId, "created", createdEvent, () -> {
                asyncTaskMapper.insert(asyncTask);
                return null;
            });
            idempotencyService.bind(idempotencyKey, taskId);
        } catch (DuplicateKeyException duplicateKeyException) {
            idempotencyService.release(idempotencyKey, taskId);
            AsyncTask persisted = asyncTaskMapper.findByIdempotencyKey(idempotencyKey);
            DetectionTask existing = persisted == null ? null : getTask(persisted.getTaskId());
            if (existing != null) {
                idempotencyService.bind(idempotencyKey, existing.getTaskId());
                completeCreation(creationFuture, existing, null);
                removeCreationFuture(idempotencyKey, creationFuture);
                return new TaskCreation(existing, false);
            }
            completeCreation(creationFuture, null, duplicateKeyException);
            removeCreationFuture(idempotencyKey, creationFuture);
            throw duplicateKeyException;
        } catch (Exception e) {
            idempotencyService.release(idempotencyKey, taskId);
            IllegalStateException failure = new IllegalStateException("任务持久化失败", e);
            completeCreation(creationFuture, null, failure);
            removeCreationFuture(idempotencyKey, creationFuture);
            throw failure;
        }

        activeTasks.put(taskId, task);
        completeCreation(creationFuture, task, null);
        removeCreationFuture(idempotencyKey, creationFuture);
        log.info("创建异步任务: taskId={}, type={}, userId={}", taskId, type, userId);
        return new TaskCreation(task, true);
    }

    private DetectionTask awaitCreation(CompletableFuture<DetectionTask> future) {
        try {
            return future.get(idempotencyWaitTimeMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待相同请求创建时被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("相同请求创建失败", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("相同请求正在创建，请稍后查询", e);
        }
    }

    private DetectionTask awaitPersistedTask(String idempotencyKey) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idempotencyWaitTimeMs);
        long sleepMs = 25L;
        do {
            String existingTaskId = idempotencyService.findTaskId(idempotencyKey).orElse(null);
            DetectionTask existing = existingTaskId == null ? null : getTask(existingTaskId);
            if (existing != null) {
                return existing;
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待相同请求创建时被中断", e);
            }
            sleepMs = Math.min(sleepMs * 2, 200L);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("相同请求正在创建，请稍后查询");
    }

    private void completeCreation(CompletableFuture<DetectionTask> future,
                                  DetectionTask task, Throwable failure) {
        if (future == null) return;
        if (failure == null) future.complete(task);
        else future.completeExceptionally(failure);
    }

    private void removeCreationFuture(String idempotencyKey,
                                      CompletableFuture<DetectionTask> future) {
        if (future != null) taskCreationFutures.remove(idempotencyKey, future);
    }

    public void updateProgress(String taskId, int progress, Object detail) {
        DetectionTask task = activeTasks.get(taskId);
        if (task == null) return;
        if (!"processing".equals(task.getStatus())) return;
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("event", "progress");
        eventData.put("status", task.getStatus());
        eventData.put("progress", progress);
        eventData.put("detail", detail);
        try {
            int updated = taskEventService.execute(taskId, "progress", eventData,
                    () -> asyncTaskMapper.updateProgressIfProcessing(taskId, progress),
                    affected -> affected == 1);
            if (updated != 1) return;
            task.setProgress(progress);
            sendSse(task, "progress", eventData);
        } catch (Exception e) {
            log.warn("DB更新失败（任务进度）: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    public void complete(String taskId, Object result) {
        DetectionTask task = activeTasks.get(taskId);
        if (task == null || !"processing".equals(task.getStatus())) return;
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("event", "completed");
        eventData.put("status", "completed");
        eventData.put("progress", 100);
        eventData.put("result", result);
        if (!persistTerminalState(taskId, "completed", result, null, "completed", eventData)) return;
        task.setStatus("completed");
        task.setProgress(100);
        task.setResult(result);
        sendSse(task, "completed", eventData);
        closeEmitter(task);
        log.info("异步任务完成: taskId={}", taskId);

        scheduleCleanup(taskId);
    }

    public void fail(String taskId, String error) {
        DetectionTask task = activeTasks.get(taskId);
        if (task == null || !"processing".equals(task.getStatus())) return;
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("event", "error");
        eventData.put("status", "failed");
        eventData.put("error", error);
        if (!persistTerminalState(taskId, "failed", null, error, "failed", eventData)) return;
        task.setStatus("failed");
        task.setError(error);
        sendSse(task, "error", eventData);
        closeEmitter(task);
        log.error("异步任务失败: taskId={}, error={}", taskId, error);

        scheduleCleanup(taskId);
    }

    public DetectionTask getTask(String taskId) {
        DetectionTask task = activeTasks.get(taskId);
        if (task == null) {
            task = readCachedTask(taskId);
        }
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

    public SseEmitter subscribe(String taskId) {
        DetectionTask current = getTask(taskId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if (current == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data("任务不存在"));
            } catch (IOException ignored) {
            }
            emitter.complete();
            return emitter;
        }
        taskEmitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> removeEmitter(taskId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        sendSnapshot(emitter, current);
        taskRefreshTasks.computeIfAbsent(taskId, ignored -> cleanupScheduler.scheduleAtFixedRate(() -> {
            DetectionTask snapshot = getTask(taskId);
            if (snapshot == null) {
                closeTaskEmitters(taskId);
                return;
            }
            for (SseEmitter subscriber : taskEmitters.getOrDefault(
                    taskId, new CopyOnWriteArrayList<>())) {
                sendSnapshot(subscriber, snapshot);
                if ("completed".equals(snapshot.getStatus()) || "failed".equals(snapshot.getStatus())) {
                    try {
                        subscriber.complete();
                    } catch (Exception ignoredException) {
                    }
                }
            }
        }, 2, 2, TimeUnit.SECONDS));
        return emitter;
    }

    public record TaskCreation(DetectionTask task, boolean created) {
    }

    public void failBeforeProcessing(String taskId, String error) {
        try {
            taskEventService.execute(taskId, "failed", Map.of(
                    "event", "error", "status", "failed", "error", error), () -> {
                AsyncTask task = asyncTaskMapper.findByTaskId(taskId);
                if (task != null && "queued".equals(task.getStatus())) {
                    task.setStatus("failed");
                    task.setErrorMessage(error);
                    task.setCompletedAt(LocalDateTime.now());
                    asyncTaskMapper.updateById(task);
                    return true;
                }
                return false;
            }, Boolean.TRUE::equals);
        } finally {
            activeTasks.remove(taskId);
        }
    }

    public DetectionTask findByIdempotencyKey(String idempotencyKey) {
        String taskId = idempotencyService.findTaskId(idempotencyKey).orElse(null);
        return taskId == null ? null : getTask(taskId);
    }

    public void bindObjectKey(String taskId, String objectKey) {
        asyncTaskMapper.bindObjectKey(taskId, objectKey);
    }

    public void bindInputLocation(String taskId, String objectKey, String filePath) {
        asyncTaskMapper.bindInputLocation(taskId, objectKey, filePath);
    }

    public void prepareRecoveredTask(AsyncTask persisted) {
        DetectionTask task = new DetectionTask();
        task.setTaskId(persisted.getTaskId());
        task.setType(persisted.getType());
        task.setStatus("queued");
        task.setProgress(0);
        activeTasks.put(persisted.getTaskId(), task);
    }

    private DetectionTask readCachedTask(String taskId) {
        String snapshot = taskEventService.getCachedSnapshot(taskId);
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(snapshot);
            com.fasterxml.jackson.databind.JsonNode payload = root.get("payload");
            if (payload == null || payload.isNull()) {
                return null;
            }
            DetectionTask task = new DetectionTask();
            task.setTaskId(taskId);
            task.setType(payload.path("type").asText(null));
            task.setStatus(payload.path("status").asText("processing"));
            task.setProgress(payload.path("progress").asInt(0));
            if (payload.hasNonNull("result")) {
                task.setResult(objectMapper.convertValue(payload.get("result"), Object.class));
            }
            task.setError(payload.path("error").asText(null));
            return task;
        } catch (Exception e) {
            log.debug("解析 Redis 任务快照失败: taskId={}, error={}", taskId, e.getMessage());
            return null;
        }
    }

    @Async("detectionTaskExecutor")
    public void runAsync(String taskId, RunnableWithTaskId runnable) {
        try {
            AsyncTask persistedTask = asyncTaskMapper.findByTaskId(taskId);
            if (persistedTask == null) {
                log.info("任务不存在，跳过执行: taskId={}", taskId);
                return;
            }
            Map<String, Object> processingEvent = Map.of(
                    "event", "processing", "status", "processing", "progress", 0);
            int acquired = taskEventService.execute(taskId, "processing", processingEvent,
                    () -> asyncTaskMapper.markProcessing(taskId, persistedTask.getVersion()),
                    affected -> affected == 1);
            if (acquired != 1) {
                log.info("任务未获得执行权，跳过重复执行: taskId={}", taskId);
                return;
            }
            DetectionTask task = activeTasks.get(taskId);
            if (task != null) {
                task.setStatus("processing");
            }
            runnable.run(taskId);
        } catch (Exception e) {
            log.error("异步任务执行异常: taskId={}", taskId, e);
            fail(taskId, e.getMessage() != null ? e.getMessage() : "任务执行失败");
        }
    }

    private void sendSse(DetectionTask task, String event, Object data) {
        for (SseEmitter emitter : taskEmitters.getOrDefault(
                task.getTaskId(), new CopyOnWriteArrayList<>())) {
            try {
                emitter.send(SseEmitter.event()
                        .id(task.getStatus() + ":" + task.getProgress())
                        .name(event).data(data));
            } catch (IOException e) {
                removeEmitter(task.getTaskId(), emitter);
            }
        }
    }

    /**
     * RocketMQ 工作消费者使用的同步执行入口。消费者线程必须持有消息处理过程，
     * 这样容量不足时才能返回 RECONSUME_LATER，而不是提前确认消息。
     */
    public boolean markQueuedForProcessing(AsyncTask persistedTask) {
        Map<String, Object> processingEvent = Map.of(
                "event", "processing", "status", "processing", "progress", 0);
        int acquired = taskEventService.execute(persistedTask.getTaskId(), "processing", processingEvent,
                () -> asyncTaskMapper.markProcessing(persistedTask.getTaskId(), persistedTask.getVersion()),
                affected -> affected == 1);
        DetectionTask task = activeTasks.computeIfAbsent(persistedTask.getTaskId(), ignored -> {
            DetectionTask created = new DetectionTask();
            created.setTaskId(persistedTask.getTaskId());
            created.setType(persistedTask.getType());
            created.setProgress(0);
            return created;
        });
        if (acquired == 1) {
            task.setStatus("processing");
            return true;
        }
        return false;
    }

    public void requeueProcessing(String taskId) {
        asyncTaskMapper.requeueProcessing(taskId);
        DetectionTask task = activeTasks.get(taskId);
        if (task != null) {
            task.setStatus("queued");
        }
    }

    private void closeEmitter(DetectionTask task) {
        closeTaskEmitters(task.getTaskId());
    }

    private void closeTaskEmitters(String taskId) {
        for (SseEmitter emitter : taskEmitters.getOrDefault(
                taskId, new CopyOnWriteArrayList<>())) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            removeEmitter(taskId, emitter);
        }
    }

    private boolean sendSnapshot(SseEmitter emitter, DetectionTask task) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", task.getTaskId());
        snapshot.put("status", task.getStatus());
        snapshot.put("progress", task.getProgress());
        snapshot.put("result", task.getResult());
        snapshot.put("error", task.getError());
        try {
            emitter.send(SseEmitter.event()
                    .id(task.getStatus() + ":" + task.getProgress())
                    .name("snapshot").data(snapshot));
            return true;
        } catch (IOException e) {
            removeEmitter(task.getTaskId(), emitter);
            return false;
        }
    }

    private void removeEmitter(String taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = taskEmitters.get(taskId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty() && taskEmitters.remove(taskId, emitters)) {
                ScheduledFuture<?> refresh = taskRefreshTasks.remove(taskId);
                if (refresh != null) refresh.cancel(false);
            }
        }
    }

    private boolean persistTerminalState(String taskId, String status, Object result, String error,
                                         String event, Map<String, Object> eventData) {
        try {
            String resultJson = result == null ? null : objectMapper.writeValueAsString(result);
            int updated = taskEventService.execute(taskId, event, eventData,
                    () -> asyncTaskMapper.finishIfProcessing(taskId, status,
                            "completed".equals(status) ? 100 : 0, resultJson, error),
                    affected -> affected == 1);
            return updated == 1;
        } catch (Exception e) {
            log.warn("持久化任务结果失败: {}", e.getMessage());
            return false;
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
