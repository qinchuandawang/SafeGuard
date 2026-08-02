package com.sdu.safeguard.service;

import com.sdu.safeguard.config.InferenceCapacityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

@Slf4j
@Service
public class InferenceCapacityService {

    private static final String KEY_PREFIX = "safeguard:inference:permits:";

    private final InferenceCapacityProperties properties;
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final MeterRegistry meterRegistry;
    private final TaskScheduler permitTaskScheduler;
    private final Map<String, Semaphore> localSemaphores = new ConcurrentHashMap<>();

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    public InferenceCapacityService(InferenceCapacityProperties properties,
                                    ObjectProvider<RedissonClient> redissonClientProvider,
                                    MeterRegistry meterRegistry,
                                    @Qualifier("inferencePermitTaskScheduler") TaskScheduler permitTaskScheduler) {
        this.properties = properties;
        this.redissonClientProvider = redissonClientProvider;
        this.meterRegistry = meterRegistry;
        this.permitTaskScheduler = permitTaskScheduler;
    }

    public <T> T execute(String category, String modelId, Supplier<T> supplier) {
        if (!properties.isEnabled()) {
            return supplier.get();
        }
        String normalizedCategory = normalizeCategory(category);
        String normalizedModelId = normalizeModelId(modelId);
        InferenceCapacityProperties.Capacity capacity = properties.resolve(normalizedCategory, normalizedModelId);
        return redisEnabled
                ? executeDistributed(normalizedCategory, normalizedModelId, capacity, supplier)
                : executeLocal(normalizedCategory, normalizedModelId, capacity, supplier);
    }

    public List<Map<String, Object>> snapshots() {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        properties.getModels().keySet().forEach(modelId -> snapshots.add(snapshot(modelId)));
        return snapshots;
    }

    private <T> T executeDistributed(String category, String modelId,
                                     InferenceCapacityProperties.Capacity capacity,
                                     Supplier<T> supplier) {
        RPermitExpirableSemaphore semaphore = distributedSemaphore(modelId);
        String permitId;
        long startedNanos = System.nanoTime();
        try {
            semaphore.trySetPermits(capacity.permits());
            permitId = semaphore.tryAcquire(capacity.waitTimeMs(), capacity.leaseMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordAcquire(category, modelId, startedNanos, "interrupted");
            throw new InferenceCapacityExceededException("等待 AI 推理资源时线程被中断", e);
        } catch (Exception e) {
            recordAcquire(category, modelId, startedNanos, "coordinator_error");
            throw new InferenceCapacityExceededException("分布式推理容量协调服务不可用", e);
        }
        if (permitId == null) {
            recordAcquire(category, modelId, startedNanos, "rejected");
            throw new InferenceCapacityExceededException("模型 " + modelId + " 的推理容量已满");
        }
        recordAcquire(category, modelId, startedNanos, "acquired");
        ScheduledFuture<?> renewal;
        try {
            renewal = scheduleRenewal(semaphore, permitId, category, modelId, capacity.leaseMs());
            if (renewal == null) {
                throw new IllegalStateException("续租调度器未返回任务句柄");
            }
        } catch (Exception e) {
            releaseDistributed(semaphore, permitId, category, modelId);
            throw new InferenceCapacityExceededException("推理许可续租调度不可用", e);
        }
        try {
            return supplier.get();
        } finally {
            try {
                renewal.cancel(false);
            } catch (Exception e) {
                log.warn("取消推理许可续租任务失败: modelId={}, permitId={}", modelId, permitId, e);
            }
            releaseDistributed(semaphore, permitId, category, modelId);
        }
    }

    private void releaseDistributed(RPermitExpirableSemaphore semaphore, String permitId,
                                    String category, String modelId) {
        try {
            if (!semaphore.tryRelease(permitId)) {
                meterRegistry.counter("safeguard.inference.capacity.release",
                        "category", category, "model", metricModel(modelId), "result", "expired").increment();
                log.warn("推理许可已过期或不存在: modelId={}, permitId={}", modelId, permitId);
            } else {
                meterRegistry.counter("safeguard.inference.capacity.release",
                        "category", category, "model", metricModel(modelId), "result", "released").increment();
            }
        } catch (Exception e) {
            meterRegistry.counter("safeguard.inference.capacity.release",
                    "category", category, "model", metricModel(modelId), "result", "error").increment();
            log.error("释放分布式推理许可失败: modelId={}, permitId={}", modelId, permitId, e);
        }
    }

    private ScheduledFuture<?> scheduleRenewal(RPermitExpirableSemaphore semaphore, String permitId,
                                                String category, String modelId, long leaseMs) {
        Duration interval = Duration.ofMillis(Math.max(1000, leaseMs / 3));
        return permitTaskScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean renewed = semaphore.updateLeaseTime(permitId, leaseMs, TimeUnit.MILLISECONDS);
                meterRegistry.counter("safeguard.inference.capacity.renew",
                        "category", category, "model", metricModel(modelId),
                        "result", renewed ? "renewed" : "missing").increment();
                if (!renewed) {
                    log.warn("推理许可续租失败，许可已不存在: modelId={}, permitId={}", modelId, permitId);
                }
            } catch (Exception e) {
                meterRegistry.counter("safeguard.inference.capacity.renew",
                        "category", category, "model", metricModel(modelId), "result", "error").increment();
                log.error("推理许可续租异常: modelId={}, permitId={}", modelId, permitId, e);
            }
        }, interval);
    }

    private <T> T executeLocal(String category, String modelId,
                               InferenceCapacityProperties.Capacity capacity,
                               Supplier<T> supplier) {
        Semaphore semaphore = localSemaphores.computeIfAbsent(modelId,
                ignored -> new Semaphore(capacity.permits(), true));
        boolean acquired;
        long startedNanos = System.nanoTime();
        try {
            acquired = semaphore.tryAcquire(capacity.waitTimeMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordAcquire(category, modelId, startedNanos, "interrupted");
            throw new InferenceCapacityExceededException("等待 AI 推理资源时线程被中断", e);
        }
        if (!acquired) {
            recordAcquire(category, modelId, startedNanos, "rejected");
            throw new InferenceCapacityExceededException("模型 " + modelId + " 的本机推理容量已满");
        }
        recordAcquire(category, modelId, startedNanos, "acquired");
        try {
            return supplier.get();
        } finally {
            semaphore.release();
            meterRegistry.counter("safeguard.inference.capacity.release",
                    "category", category, "model", metricModel(modelId), "result", "released").increment();
        }
    }

    private Map<String, Object> snapshot(String modelId) {
        String category = categoryOf(modelId);
        InferenceCapacityProperties.Capacity capacity = properties.resolve(category, modelId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelId", modelId);
        result.put("category", category);
        result.put("mode", redisEnabled ? "distributed" : "local");
        result.put("configuredPermits", capacity.permits());
        result.put("waitTimeMs", capacity.waitTimeMs());
        result.put("leaseMs", capacity.leaseMs());
        if (!properties.isEnabled()) {
            result.put("status", "disabled");
            return result;
        }
        try {
            if (redisEnabled) {
                RPermitExpirableSemaphore semaphore = distributedSemaphore(modelId);
                semaphore.trySetPermits(capacity.permits());
                result.put("availablePermits", semaphore.availablePermits());
                result.put("acquiredPermits", semaphore.acquiredPermits());
            } else {
                Semaphore semaphore = localSemaphores.computeIfAbsent(modelId,
                        ignored -> new Semaphore(capacity.permits(), true));
                result.put("availablePermits", semaphore.availablePermits());
                result.put("acquiredPermits", capacity.permits() - semaphore.availablePermits());
            }
            result.put("status", "ready");
        } catch (Exception e) {
            result.put("status", "unavailable");
            result.put("error", e.getClass().getSimpleName());
        }
        return result;
    }

    private RPermitExpirableSemaphore distributedSemaphore(String modelId) {
        RedissonClient client = redissonClientProvider.getIfAvailable();
        if (client == null) {
            throw new InferenceCapacityExceededException("Redis 已启用但 RedissonClient 不可用");
        }
        return client.getPermitExpirableSemaphore(KEY_PREFIX + modelId);
    }

    private void recordAcquire(String category, String modelId, long startedNanos, String result) {
        String metricModel = metricModel(modelId);
        meterRegistry.counter("safeguard.inference.capacity.acquire",
                "category", category, "model", metricModel, "result", result).increment();
        Timer.builder("safeguard.inference.capacity.wait")
                .tags("category", category, "model", metricModel, "result", result)
                .register(meterRegistry)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    private String metricModel(String modelId) {
        return properties.getModels().containsKey(modelId) ? modelId : "unconfigured";
    }

    private String categoryOf(String modelId) {
        InferenceCapacityProperties.ModelCapacity capacity = properties.getModels().get(modelId);
        if (capacity == null || capacity.getCategory() == null) {
            throw new IllegalStateException("模型容量配置缺少 category: " + modelId);
        }
        return normalizeCategory(capacity.getCategory());
    }

    private String normalizeCategory(String category) {
        if (!"audio".equalsIgnoreCase(category) && !"video".equalsIgnoreCase(category)) {
            throw new IllegalArgumentException("推理资源类型仅支持 audio 或 video");
        }
        return category.toLowerCase();
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null || !modelId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("模型 ID 格式不合法");
        }
        return modelId;
    }
}
