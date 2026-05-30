package com.sdu.safeguard.agent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册表 — SPI 机制。
 * 实现 {@link Agent} 接口并用 @Component 注册的 Bean 自动被收集到此处。
 * {@link AgentOrchestrator} 通过此注册表查找 Agent，不再使用硬编码 switch。
 */
@Slf4j
@Component
public class AgentRegistry {

    private final Map<String, Agent> agentMap = new ConcurrentHashMap<>();

    public AgentRegistry(List<Agent> agents) {
        for (Agent agent : agents) {
            String type = agent.getType();
            if (agentMap.containsKey(type)) {
                log.warn("Agent类型重复注册: {}, 将覆盖", type);
            }
            agentMap.put(type, agent);
            log.debug("注册Agent: {}", type);
        }
    }

    @PostConstruct
    public void init() {
        log.info("Agent注册表初始化完成: {} 个Agent: {}", agentMap.size(), agentMap.keySet());
    }

    public Agent getAgent(String type) {
        return agentMap.get(type);
    }

    public boolean hasAgent(String type) {
        return agentMap.containsKey(type);
    }

    public List<String> getAvailableTypes() {
        return List.copyOf(agentMap.keySet());
    }
}