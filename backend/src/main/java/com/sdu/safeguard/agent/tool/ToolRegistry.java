package com.sdu.safeguard.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册表 — Spring 自动收集所有 {@link Tool} Bean。
 * 提供上下文感知的工具描述，用于构建 LLM Prompt。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool tool : toolBeans) {
            String name = tool.getName();
            if (tools.containsKey(name)) {
                log.warn("工具名称重复: {}, 将覆盖", name);
            }
            tools.put(name, tool);
            log.debug("注册工具: {} - {}", name, tool.getDescription());
        }
    }

    @PostConstruct
    public void init() {
        log.info("工具注册表初始化完成: {} 个工具: {}", tools.size(), tools.keySet());
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public List<String> getToolNames() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 构建工具描述块的 Markdown 文本，注入 LLM Prompt。
     * @param contextType 上下文类型: "text" 仅文本工具, "audio" 包含音频工具, "video" 包含视频工具, null 为全部
     */
    public String buildToolDescriptions(String contextType) {
        return tools.values().stream()
                .filter(t -> shouldInclude(t, contextType))
                .map(t -> String.format("- **%s**: %s", t.getName(), t.getDescription()))
                .collect(Collectors.joining("\n"));
    }

    /** 默认全部工具 */
    public String buildToolDescriptions() {
        return buildToolDescriptions(null);
    }

    private boolean shouldInclude(Tool tool, String contextType) {
        if (contextType == null) return true;
        String name = tool.getName();
        return switch (contextType) {
            case "text" -> !name.contains("AUDIO") && !name.contains("VIDEO");
            case "audio" -> !name.contains("VIDEO");
            case "video" -> !name.contains("AUDIO");
            default -> true;
        };
    }
}