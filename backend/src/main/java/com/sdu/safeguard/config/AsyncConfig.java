package com.sdu.safeguard.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class AsyncConfig {

    private final MeterRegistry meterRegistry;
    private final AtomicLong detectionRejected = new AtomicLong();
    private volatile ThreadPoolExecutor detectionPool;

    public AsyncConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 通用检测任务线程池：处理 Agent 编排、LLM 调用、检测任务等 I/O 密集型操作
     */
    @Bean(name = "detectionTaskExecutor")
    public ThreadPoolTaskExecutor detectionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("detection-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        // 保持计数器强引用，避免 Micrometer 弱引用回收后暴露 NaN。
        meterRegistry.gauge("safeguard.executor.rejected", detectionRejected);
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            detectionRejected.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("检测任务队列已满");
        });
        executor.initialize();
        bindExecutorMetrics(executor, "detection");
        return executor;
    }

    /**
     * 视频帧处理专用线程池：隔离视频帧并行推理任务，避免占满主线程池
     */
    @Bean(name = "videoFrameExecutor")
    public ThreadPoolTaskExecutor videoFrameExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 视频模型占用显存/内存较高，演示环境优先稳定，逐帧串行调用 Python 服务。
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(30);
        executor.setThreadNamePrefix("video-frame-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * SSE 流式调用专用线程池：隔离 LLM 流式响应的长连接
     */
    @Bean(name = "streamExecutor")
    public ThreadPoolTaskExecutor streamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("stream-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 推理许可续租调度器：业务进程存活时延长许可，进程退出后由 Redis 租约自动回收。
     */
    @Bean(name = "inferencePermitTaskScheduler")
    public ThreadPoolTaskScheduler inferencePermitTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("inference-permit-renew-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    private void bindExecutorMetrics(ThreadPoolTaskExecutor executor, String name) {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        if ("detection".equals(name)) {
            detectionPool = pool;
        }
        meterRegistry.gauge("safeguard.executor.active", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("executor", name)), this,
                ignored -> detectionPool == null ? 0 : detectionPool.getActiveCount());
        meterRegistry.gauge("safeguard.executor.queue.size", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("executor", name)), this,
                ignored -> detectionPool == null ? 0 : detectionPool.getQueue().size());
        meterRegistry.gauge("safeguard.executor.queue.remaining", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("executor", name)), this,
                ignored -> detectionPool == null ? 0 : detectionPool.getQueue().remainingCapacity());
    }
}
