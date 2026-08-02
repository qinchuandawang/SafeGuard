package com.sdu.safeguard.service;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.sdu.safeguard.config.SentinelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalCallGuardTest {

    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void 瞬时失败时按配置执行有限重试() {
        SentinelProperties properties = properties();
        properties.getRetry().setMaxAttempts(2);
        properties.getRetry().setBackoffMs(0);
        ExternalCallGuard guard = new ExternalCallGuard(properties);
        AtomicInteger calls = new AtomicInteger();

        String result = guard.execute("retry-test", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("瞬时故障");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void Sentinel拒绝资源时不执行下游调用和重试() {
        SentinelProperties properties = properties();
        ExternalCallGuard guard = new ExternalCallGuard(properties);
        AtomicInteger calls = new AtomicInteger();
        FlowRuleManager.loadRules(List.of(new FlowRule("blocked-test")
                .setGrade(RuleConstant.FLOW_GRADE_THREAD)
                .setCount(0)));

        assertThatThrownBy(() -> guard.execute("blocked-test", () -> {
            calls.incrementAndGet();
            return "unreachable";
        })).isInstanceOf(ExternalDependencyBlockedException.class);
        assertThat(calls).hasValue(0);
    }

    private SentinelProperties properties() {
        SentinelProperties properties = new SentinelProperties();
        properties.setEnabled(true);
        properties.getRetry().setMaxAttempts(1);
        properties.getRetry().setBackoffMs(0);
        return properties;
    }
}
