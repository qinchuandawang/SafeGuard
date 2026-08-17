package com.sdu.safeguard.agent.tool;

import com.sdu.safeguard.dto.ReActThought;
import com.sdu.safeguard.service.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI 原生 Tool Calling 的工具编排服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FunctionCallingToolService {

    private final ToolRegistry toolRegistry;
    private final LLMService llmService;

    public List<ReActThought> execute(String input, String context, String sessionId, String contextType) {
        List<Tool> tools = toolRegistry.getTools(contextType);
        if (tools.isEmpty()) {
            return List.of();
        }

        List<ToolObservation> observations = new ArrayList<>();
        List<ToolCallback> callbacks = tools.stream()
                .map(tool -> createCallback(tool, input, sessionId, observations))
                .toList();

        try {
            String answer = llmService.completeWithTools(
                    "你是 SafeGuard 反诈骗分析助手。请按需调用工具获取证据，禁止虚构工具结果；完成后输出简洁、可执行的中文风险结论。",
                    "用户问题：\n" + input + "\n\n已检索上下文：\n" + safe(context),
                    callbacks, "function-calling");

            List<ReActThought> thoughts = new ArrayList<>();
            for (int index = 0; index < observations.size(); index++) {
                ToolObservation observation = observations.get(index);
                thoughts.add(ReActThought.builder()
                        .step(index + 1)
                        .thought("Spring AI 由模型选择工具")
                        .action(observation.toolName())
                        .actionInput(observation.input())
                        .observation(observation.output())
                        .referencedSources(List.of())
                        .isFinal(false)
                        .build());
            }
            thoughts.add(ReActThought.builder()
                    .step(thoughts.size() + 1)
                    .thought("模型汇总工具证据并形成结论")
                    .action("FINISH")
                    .actionInput("")
                    .observation(safe(answer))
                    .finalAnswer(safe(answer))
                    .referencedSources(List.of())
                    .isFinal(true)
                    .build());
            return thoughts;
        } catch (Exception exception) {
            log.warn("Spring AI Tool Calling 执行失败: {}", exception.getMessage());
            return List.of();
        }
    }

    private ToolCallback createCallback(Tool tool, String originalInput, String sessionId,
                                        List<ToolObservation> observations) {
        return FunctionToolCallback.builder(tool.getName(), (ToolInput toolInput) -> {
                    String actualInput = toolInput == null || isBlank(toolInput.input())
                            ? originalInput : toolInput.input();
                    Map<String, Object> parameters = new LinkedHashMap<>();
                    parameters.put("sessionId", sessionId);
                    parameters.put("originalInput", originalInput);
                    ToolResult result = tool.execute(ToolExecutionRequest.builder()
                            .toolName(tool.getName())
                            .input(actualInput)
                            .filePath(toolInput == null ? null : toolInput.filePath())
                            .parameters(parameters)
                            .build());
                    String output = result.isSuccess() ? safe(result.getOutput()) : "工具执行失败：" + safe(result.getError());
                    synchronized (observations) {
                        observations.add(new ToolObservation(tool.getName(), actualInput, output));
                    }
                    return output;
                })
                .description(tool.getDescription())
                .inputType(ToolInput.class)
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record ToolInput(String input, String filePath) {
    }

    private record ToolObservation(String toolName, String input, String output) {
    }
}
