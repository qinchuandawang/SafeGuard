package com.sdu.safeguard.agent;

import com.sdu.safeguard.dto.AgentRequest;
import com.sdu.safeguard.dto.AgentResponse;

/**
 * Agent SPI 接口 — 所有 Agent 类型实现此接口。
 * 通过 {@link org.springframework.stereotype.Component} 注解注册，
 * 由 {@link AgentRegistry} 统一管理。
 */
public interface Agent {

    /** 返回 Agent 类型标识，如 "TEXT_ANALYSIS" */
    String getType();

    /** 执行 Agent 逻辑 */
    AgentResponse execute(AgentRequest request);
}