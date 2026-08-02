package com.sdu.safeguard.service;

import com.sdu.safeguard.config.TokenCostProperties;
import com.sdu.safeguard.entity.LlmUsageRecord;
import com.sdu.safeguard.mapper.LlmUsageRecordMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class TokenCostService {

    private final TokenCostProperties properties;
    private final ObjectProvider<LlmUsageRecordMapper> mapperProvider;
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final MeterRegistry meterRegistry;
    private final AtomicLong todayTokens = new AtomicLong();
    private volatile LocalDate counterDate;

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    public TokenCostService(TokenCostProperties properties,
                            ObjectProvider<LlmUsageRecordMapper> mapperProvider,
                            ObjectProvider<RedissonClient> redissonClientProvider,
                            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.mapperProvider = mapperProvider;
        this.redissonClientProvider = redissonClientProvider;
        this.meterRegistry = meterRegistry;
    }

    public Reservation reserve(String scene, String model, String prompt, int maxCompletionTokens) {
        int promptTokens = estimateTokens(prompt);
        if (!properties.isEnabled()) {
            return new Reservation(UUID.randomUUID().toString(), scene, model, promptTokens, 0);
        }
        if (promptTokens > properties.getMaxPromptTokens()) {
            throw new TokenBudgetExceededException("输入上下文超过 Token 上限");
        }
        refreshDailyCounterIfNeeded();
        int reserved = Math.max(0, promptTokens) + Math.max(0, maxCompletionTokens);
        if (redisEnabled) {
            reserveDistributed(reserved, scene);
        } else {
            reserveLocal(reserved, scene);
        }
        return new Reservation(UUID.randomUUID().toString(), safeScene(scene), model, promptTokens, reserved);
    }

    public void complete(Reservation reservation, Integer actualPromptTokens,
                         Integer actualCompletionTokens, boolean cacheHit, String status) {
        int promptTokens = actualPromptTokens == null || actualPromptTokens < 0
                ? reservation.estimatedPromptTokens() : actualPromptTokens;
        int completionTokens = actualCompletionTokens == null || actualCompletionTokens < 0
                ? Math.max(0, reservation.reservedTokens() - reservation.estimatedPromptTokens())
                : actualCompletionTokens;
        int actualTotal = cacheHit ? 0 : promptTokens + completionTokens;
        if (properties.isEnabled()) {
            adjustCounter(actualTotal - reservation.reservedTokens());
        }
        persist(reservation, promptTokens, completionTokens, actualTotal, cacheHit, status);
        meterRegistry.counter("safeguard.llm.requests", "scene", reservation.scene(),
                "status", status, "cache", Boolean.toString(cacheHit)).increment();
        meterRegistry.counter("safeguard.llm.tokens", "scene", reservation.scene(),
                "type", "total").increment(actualTotal);
    }

    public void cancel(Reservation reservation, String status) {
        if (properties.isEnabled()) {
            adjustCounter(-reservation.reservedTokens());
        }
        persist(reservation, 0, 0, 0, false, status);
    }

    public boolean isCacheable(String scene) {
        return properties.isResponseCacheEnabled() && properties.getCacheableScenes().contains(scene);
    }

    public String promptVersion() {
        return properties.getPromptVersion();
    }

    public Map<String, Object> snapshot() {
        refreshDailyCounterIfNeeded();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", counterDate);
        long used = currentCounter();
        result.put("usedAndReservedTokens", used);
        result.put("dailyTokenBudget", properties.getDailyTokenBudget());
        result.put("remainingTokens", used < 0 ? null
                : Math.max(0, properties.getDailyTokenBudget() - used));
        result.put("mode", redisEnabled ? "distributed" : "local");
        result.put("enabled", properties.isEnabled());
        result.put("status", used < 0 ? "unavailable" : "ready");
        return result;
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.codePointCount(0, text.length());
    }

    private synchronized void refreshDailyCounterIfNeeded() {
        LocalDate today = LocalDate.now();
        if (today.equals(counterDate)) return;
        long persisted = 0;
        try {
            LlmUsageRecordMapper mapper = mapperProvider.getIfAvailable();
            if (mapper != null) persisted = mapper.sumTodayTokens();
        } catch (Exception e) {
            log.warn("读取当日 Token 用量失败，使用本机计数: {}", e.getMessage());
        }
        todayTokens.set(Math.max(0, persisted));
        counterDate = today;
    }

    private void reserveLocal(int reserved, String scene) {
        while (true) {
            long current = todayTokens.get();
            if (current + reserved > properties.getDailyTokenBudget()) {
                rejectBudget(scene);
            }
            if (todayTokens.compareAndSet(current, current + reserved)) return;
        }
    }

    private void reserveDistributed(int reserved, String scene) {
        try {
            RAtomicLong counter = distributedCounter();
            counter.compareAndSet(0, todayTokens.get());
            counter.expire(Duration.ofDays(2));
            while (true) {
                long current = counter.get();
                if (current + reserved > properties.getDailyTokenBudget()) rejectBudget(scene);
                if (counter.compareAndSet(current, current + reserved)) return;
            }
        } catch (TokenBudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenBudgetExceededException("Token 预算协调服务不可用，已停止大模型调用");
        }
    }

    private void adjustCounter(long delta) {
        if (redisEnabled) {
            try {
                RAtomicLong counter = distributedCounter();
                while (true) {
                    long current = counter.get();
                    long adjusted = Math.max(0, current + delta);
                    if (counter.compareAndSet(current, adjusted)) break;
                }
            } catch (Exception e) {
                log.error("调整分布式 Token 预算失败: delta={}", delta, e);
            }
        } else {
            todayTokens.updateAndGet(current -> Math.max(0, current + delta));
        }
    }

    private long currentCounter() {
        if (!redisEnabled) return todayTokens.get();
        try {
            return distributedCounter().get();
        } catch (Exception e) {
            return -1;
        }
    }

    private void rejectBudget(String scene) {
        meterRegistry.counter("safeguard.llm.budget.rejected", "scene", safeScene(scene)).increment();
        throw new TokenBudgetExceededException("今日大模型 Token 预算已用尽");
    }

    private String redisBudgetKey() {
        return "safeguard:llm:token-budget:" + LocalDate.now();
    }

    private RAtomicLong distributedCounter() {
        RedissonClient client = redissonClientProvider.getIfAvailable();
        if (client == null) throw new IllegalStateException("RedissonClient 不可用");
        return client.getAtomicLong(redisBudgetKey());
    }

    private void persist(Reservation reservation, int promptTokens, int completionTokens,
                         int totalTokens, boolean cacheHit, String status) {
        try {
            LlmUsageRecordMapper mapper = mapperProvider.getIfAvailable();
            if (mapper == null) return;
            mapper.insert(LlmUsageRecord.builder()
                    .requestId(reservation.requestId())
                    .scene(reservation.scene())
                    .model(reservation.model())
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .cacheHit(cacheHit)
                    .status(status)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("持久化 Token 用量失败: requestId={}, error={}", reservation.requestId(), e.getMessage());
        }
    }

    private String safeScene(String scene) {
        return scene == null || scene.isBlank() ? "general" : scene;
    }

    public record Reservation(String requestId, String scene, String model,
                              int estimatedPromptTokens, int reservedTokens) {
    }
}
