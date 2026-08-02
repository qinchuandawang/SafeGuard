package com.sdu.safeguard.service;

import com.sdu.safeguard.entity.DetectionRecord;
import com.sdu.safeguard.mapper.DetectionRecordMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class ArchiveService {

    private static final String LOCK_KEY = "safeguard:archive:detection-record";

    private final DetectionRecordMapper detectionRecordMapper;
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public ArchiveService(DetectionRecordMapper detectionRecordMapper,
                          ObjectProvider<RedissonClient> redissonClientProvider,
                          MeterRegistry meterRegistry,
                          PlatformTransactionManager transactionManager) {
        this.detectionRecordMapper = detectionRecordMapper;
        this.redissonClientProvider = redissonClientProvider;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Value("${archive.detection.hot-days:30}")
    private int hotDays;

    @Value("${archive.detection.batch-size:500}")
    private int batchSize;

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    public Map<String, Object> runOneBatch() {
        LockAttempt lockAttempt = acquireLock();
        if (!lockAttempt.acquired()) {
            return Map.of("executed", false, "reason", "已有归档任务正在执行");
        }
        long startedAt = System.nanoTime();
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> archiveBatch()));
        } catch (RuntimeException exception) {
            meterRegistry.counter("safeguard.archive.failures").increment();
            throw exception;
        } finally {
            meterRegistry.timer("safeguard.archive.duration").record(
                    Duration.ofNanos(System.nanoTime() - startedAt));
            releaseLock(lockAttempt.lock());
        }
    }

    private Map<String, Object> archiveBatch() {
        LocalDateTime before = LocalDateTime.now().minusDays(hotDays);
        List<DetectionRecord> batch = detectionRecordMapper.findArchiveBatch(0, before, batchSize);
        if (batch.isEmpty()) {
            return Map.of("executed", true, "archived", 0, "hotDays", hotDays);
        }
        long maxId = batch.get(batch.size() - 1).getId();
        detectionRecordMapper.copyArchiveRange(0, maxId, before);
        long archivedCount = detectionRecordMapper.countArchiveRange(0, maxId);
        if (archivedCount < batch.size()) {
            throw new IllegalStateException("归档副本校验失败，拒绝删除热表数据");
        }
        int deleted = detectionRecordMapper.markArchiveRangeDeleted(0, maxId, before);
        meterRegistry.counter("safeguard.archive.records").increment(deleted);
        return Map.of("executed", true, "archived", deleted, "maxId", maxId, "hotDays", hotDays);
    }

    public Map<String, Object> stats() {
        return Map.of(
                "hot", detectionRecordMapper.countHotRecords(),
                "archived", detectionRecordMapper.countArchivedRecords(),
                "hotDays", hotDays,
                "batchSize", batchSize
        );
    }

    private LockAttempt acquireLock() {
        if (!redisEnabled) {
            return new LockAttempt(true, null);
        }
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            return new LockAttempt(false, null);
        }
        try {
            RLock lock = redissonClient.getLock(LOCK_KEY);
            return new LockAttempt(lock.tryLock(), lock);
        } catch (RuntimeException exception) {
            log.warn("获取归档分布式锁失败，拒绝执行本批次: {}", exception.getMessage());
            return new LockAttempt(false, null);
        }
    }

    private void releaseLock(RLock lock) {
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException exception) {
            log.warn("释放归档分布式锁失败，将由 Redisson 锁超时机制恢复: {}", exception.getMessage());
        }
    }

    private record LockAttempt(boolean acquired, RLock lock) {
    }
}
