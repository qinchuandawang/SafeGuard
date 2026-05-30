package com.sdu.safeguard.agent.tool;

/**
 * 工具接口 — 可被 ReAct 循环调用的真实能力。
 * 实现类通过 @Component 注册，由 {@link ToolRegistry} 自动收集。
 */
public interface Tool {

    /** 工具名称，如 "SEARCH_KNOWLEDGE"、"DETECT_AUDIO" */
    String getName();

    /** 工具描述，用于构建 LLM Prompt 使模型了解何时调用此工具 */
    String getDescription();

    /** 执行工具 */
    ToolResult execute(ToolExecutionRequest request);
}