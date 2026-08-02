package com.sdu.safeguard.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.sdu.safeguard.config.SentinelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class ExternalCallGuard {

    private final SentinelProperties properties;

    public <T> T execute(String dependencyName, Supplier<T> supplier) {
        if (!properties.isEnabled()) {
            return supplier.get();
        }

        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeOnce(dependencyName, supplier);
            } catch (ExternalDependencyBlockedException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (attempt < maxAttempts) {
                    backoff();
                }
            }
        }
        throw lastFailure == null ? new IllegalStateException("外部依赖调用失败: " + dependencyName) : lastFailure;
    }

    private <T> T executeOnce(String dependencyName, Supplier<T> supplier) {
        Entry entry = null;
        try {
            entry = SphU.entry(dependencyName, EntryType.OUT);
            return supplier.get();
        } catch (BlockException exception) {
            throw new ExternalDependencyBlockedException(dependencyName, exception);
        } catch (RuntimeException exception) {
            Tracer.trace(exception);
            throw exception;
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private void backoff() {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(0, properties.getRetry().getBackoffMs()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("外部依赖重试等待被中断", exception);
        }
    }
}
