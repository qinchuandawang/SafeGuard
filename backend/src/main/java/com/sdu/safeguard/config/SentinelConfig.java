package com.sdu.safeguard.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SentinelConfig {

    private final SentinelProperties properties;

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) {
            log.info("Sentinel 外部调用保护已关闭");
            return;
        }

        System.setProperty("project.name", properties.getProjectName());
        System.setProperty("csp.sentinel.dashboard.server", properties.getDashboard());
        System.setProperty("csp.sentinel.api.port", String.valueOf(properties.getApiPort()));

        List<FlowRule> flowRules = new ArrayList<>();
        List<DegradeRule> degradeRules = new ArrayList<>();
        properties.getResources().forEach((resource, rule) -> {
            flowRules.add(new FlowRule(resource)
                    .setGrade(RuleConstant.FLOW_GRADE_THREAD)
                    .setCount(rule.getMaxConcurrent()));

            degradeRules.add(new DegradeRule(resource)
                    .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                    .setCount(rule.getSlowCallThresholdMs())
                    .setSlowRatioThreshold(rule.getSlowCallRatioThreshold())
                    .setMinRequestAmount(rule.getMinimumRequestAmount())
                    .setStatIntervalMs(rule.getStatIntervalMs())
                    .setTimeWindow(rule.getOpenDurationSeconds()));

            degradeRules.add(new DegradeRule(resource)
                    .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                    .setCount(rule.getExceptionRatioThreshold())
                    .setMinRequestAmount(rule.getMinimumRequestAmount())
                    .setStatIntervalMs(rule.getStatIntervalMs())
                    .setTimeWindow(rule.getOpenDurationSeconds()));
        });

        FlowRuleManager.loadRules(flowRules);
        DegradeRuleManager.loadRules(degradeRules);
        log.info("Sentinel 初始化完成: dashboard={}, resources={}",
                properties.getDashboard(), properties.getResources().keySet());
    }
}
