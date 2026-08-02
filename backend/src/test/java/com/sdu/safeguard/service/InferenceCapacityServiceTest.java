package com.sdu.safeguard.service;

import com.sdu.safeguard.config.InferenceCapacityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InferenceCapacityServiceTest {

    @Mock
    private ObjectProvider<RedissonClient> redissonClientProvider;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RPermitExpirableSemaphore distributedSemaphore;
    @Mock
    private TaskScheduler permitTaskScheduler;
    @Mock
    private ScheduledFuture<?> renewalFuture;

    private InferenceCapacityProperties properties;
    private InferenceCapacityService service;

    @BeforeEach
    void setUp() {
        properties = new InferenceCapacityProperties();
        properties.setWaitTimeMs(0);
        InferenceCapacityProperties.ModelCapacity modelCapacity =
                new InferenceCapacityProperties.ModelCapacity();
        modelCapacity.setCategory("video");
        modelCapacity.setPermits(1);
        modelCapacity.setLeaseMs(300_000L);
        properties.getModels().put("xception-ffpp", modelCapacity);

        service = new InferenceCapacityService(
                properties, redissonClientProvider, new SimpleMeterRegistry(), permitTaskScheduler);
        ReflectionTestUtils.setField(service, "redisEnabled", false);
        lenient().doReturn(renewalFuture).when(permitTaskScheduler).scheduleAtFixedRate(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    void Redis关闭时使用本机公平信号量限制并发() {
        AtomicBoolean nestedExecuted = new AtomicBoolean();

        String result = service.execute("video", "xception-ffpp", () -> {
            assertThatThrownBy(() -> service.execute("video", "xception-ffpp", () -> {
                nestedExecuted.set(true);
                return "nested";
            })).isInstanceOf(InferenceCapacityExceededException.class);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(nestedExecuted).isFalse();
    }

    @Test
    void 本机下游异常后仍释放许可() {
        assertThatThrownBy(() -> service.execute("video", "xception-ffpp", () -> {
            throw new IllegalStateException("推理失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(service.execute("video", "xception-ffpp", () -> "recovered"))
                .isEqualTo("recovered");
    }

    @Test
    void Redis开启时使用可过期分布式许可() throws InterruptedException {
        ReflectionTestUtils.setField(service, "redisEnabled", true);
        when(redissonClientProvider.getIfAvailable()).thenReturn(redissonClient);
        when(redissonClient.getPermitExpirableSemaphore(
                "safeguard:inference:permits:xception-ffpp")).thenReturn(distributedSemaphore);
        when(distributedSemaphore.tryAcquire(0, 300_000, TimeUnit.MILLISECONDS))
                .thenReturn("permit-1");
        when(distributedSemaphore.tryRelease("permit-1")).thenReturn(true);
        when(distributedSemaphore.updateLeaseTime("permit-1", 300_000, TimeUnit.MILLISECONDS))
                .thenReturn(true);

        assertThat(service.execute("video", "xception-ffpp", () -> "ok")).isEqualTo("ok");

        verify(distributedSemaphore).trySetPermits(1);
        verify(distributedSemaphore).tryAcquire(0, 300_000, TimeUnit.MILLISECONDS);
        verify(distributedSemaphore).tryRelease("permit-1");
        verify(renewalFuture).cancel(false);
        ArgumentCaptor<Runnable> renewalTask = ArgumentCaptor.forClass(Runnable.class);
        verify(permitTaskScheduler).scheduleAtFixedRate(
                renewalTask.capture(), eq(Duration.ofMillis(100_000)));
        renewalTask.getValue().run();
        verify(distributedSemaphore).updateLeaseTime(
                "permit-1", 300_000, TimeUnit.MILLISECONDS);
    }

    @Test
    void 分布式容量耗尽时不执行下游() throws InterruptedException {
        ReflectionTestUtils.setField(service, "redisEnabled", true);
        when(redissonClientProvider.getIfAvailable()).thenReturn(redissonClient);
        when(redissonClient.getPermitExpirableSemaphore(
                "safeguard:inference:permits:xception-ffpp")).thenReturn(distributedSemaphore);
        when(distributedSemaphore.tryAcquire(0, 300_000, TimeUnit.MILLISECONDS)).thenReturn(null);
        AtomicBoolean executed = new AtomicBoolean();

        assertThatThrownBy(() -> service.execute("video", "xception-ffpp", () -> {
            executed.set(true);
            return "unreachable";
        })).isInstanceOf(InferenceCapacityExceededException.class);

        assertThat(executed).isFalse();
        verify(distributedSemaphore, never()).tryRelease(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void Redis开启但协调客户端不可用时失败关闭() {
        ReflectionTestUtils.setField(service, "redisEnabled", true);
        when(redissonClientProvider.getIfAvailable()).thenReturn(null);
        AtomicBoolean executed = new AtomicBoolean();

        assertThatThrownBy(() -> service.execute("video", "xception-ffpp", () -> {
            executed.set(true);
            return "unreachable";
        })).isInstanceOf(InferenceCapacityExceededException.class)
                .hasMessageContaining("RedissonClient");

        assertThat(executed).isFalse();
    }

    @Test
    void 分布式下游异常后仍释放许可() throws InterruptedException {
        ReflectionTestUtils.setField(service, "redisEnabled", true);
        when(redissonClientProvider.getIfAvailable()).thenReturn(redissonClient);
        when(redissonClient.getPermitExpirableSemaphore(
                "safeguard:inference:permits:xception-ffpp")).thenReturn(distributedSemaphore);
        when(distributedSemaphore.tryAcquire(0, 300_000, TimeUnit.MILLISECONDS))
                .thenReturn("permit-2");
        when(distributedSemaphore.tryRelease("permit-2")).thenReturn(true);

        assertThatThrownBy(() -> service.execute("video", "xception-ffpp", () -> {
            throw new IllegalStateException("推理失败");
        })).isInstanceOf(IllegalStateException.class);

        verify(distributedSemaphore).tryRelease("permit-2");
    }

    @Test
    void 续租调度失败时立即释放已获取许可() throws InterruptedException {
        ReflectionTestUtils.setField(service, "redisEnabled", true);
        when(redissonClientProvider.getIfAvailable()).thenReturn(redissonClient);
        when(redissonClient.getPermitExpirableSemaphore(
                "safeguard:inference:permits:xception-ffpp")).thenReturn(distributedSemaphore);
        when(distributedSemaphore.tryAcquire(0, 300_000, TimeUnit.MILLISECONDS))
                .thenReturn("permit-3");
        when(distributedSemaphore.tryRelease("permit-3")).thenReturn(true);
        when(permitTaskScheduler.scheduleAtFixedRate(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("调度器已关闭"));
        AtomicBoolean executed = new AtomicBoolean();

        assertThatThrownBy(() -> service.execute("video", "xception-ffpp", () -> {
            executed.set(true);
            return "unreachable";
        })).isInstanceOf(InferenceCapacityExceededException.class)
                .hasMessageContaining("续租调度");

        assertThat(executed).isFalse();
        verify(distributedSemaphore).tryRelease("permit-3");
    }
}
