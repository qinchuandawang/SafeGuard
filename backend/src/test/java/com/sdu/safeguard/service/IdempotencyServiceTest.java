package com.sdu.safeguard.service;

import com.sdu.safeguard.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ObjectProvider<RedissonClient> redissonClientProvider;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> bucket;
    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(redissonClientProvider, asyncTaskMapper);
        ReflectionTestUtils.setField(service, "redisEnabled", true);
        ReflectionTestUtils.setField(service, "ttlSeconds", 600L);
        when(redissonClientProvider.getIfAvailable()).thenReturn(redissonClient);
        when(redissonClient.<String>getBucket("safeguard:idempotency:key-1")).thenReturn(bucket);
    }

    @Test
    void 预占幂等键使用Redisson原子写入() {
        when(bucket.trySet("task-1", 600, TimeUnit.SECONDS)).thenReturn(true);

        assertThat(service.reserve("key-1", "task-1")).isTrue();
        verify(bucket).trySet("task-1", 600, TimeUnit.SECONDS);
    }

    @Test
    void 释放幂等键使用CAS避免误删其他任务() {
        service.release("key-1", "task-1");

        verify(bucket).compareAndSet("task-1", null);
    }
}
