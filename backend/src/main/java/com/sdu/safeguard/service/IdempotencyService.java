package com.sdu.safeguard.service;

import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final AsyncTaskMapper asyncTaskMapper;

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${task.idempotency.ttl-seconds:600}")
    private long ttlSeconds;

    public Optional<String> findTaskId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        if (redisEnabled) {
            try {
                RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
                if (redissonClient != null) {
                    String taskId = redissonClient.<String>getBucket(redisKey(idempotencyKey)).get();
                    if (taskId != null && !taskId.isBlank()) {
                        return Optional.of(taskId);
                    }
                }
            } catch (Exception ignored) {
                // Redis 不可用时由数据库唯一约束兜底。
            }
        }
        AsyncTask task = asyncTaskMapper.findByIdempotencyKey(idempotencyKey);
        return task == null ? Optional.empty() : Optional.of(task.getTaskId());
    }

    public boolean reserve(String idempotencyKey, String taskId) {
        if (!redisEnabled || idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        try {
            RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
            if (redissonClient == null) {
                return true;
            }
            return redissonClient.<String>getBucket(redisKey(idempotencyKey))
                    .trySet(taskId, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return true;
        }
    }

    public void bind(String idempotencyKey, String taskId) {
        if (!redisEnabled || idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        try {
            RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
            if (redissonClient != null) {
                redissonClient.<String>getBucket(redisKey(idempotencyKey))
                        .set(taskId, ttlSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // MySQL 中的幂等键仍是最终防线。
        }
    }

    public void release(String idempotencyKey, String taskId) {
        if (!redisEnabled || idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        try {
            RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
            if (redissonClient != null) {
                RBucket<String> bucket = redissonClient.getBucket(redisKey(idempotencyKey));
                bucket.compareAndSet(taskId, null);
            }
        } catch (Exception ignored) {
        }
    }

    private String redisKey(String idempotencyKey) {
        return "safeguard:idempotency:" + idempotencyKey;
    }
}
