package com.sdu.safeguard.agent.tool;

import com.sdu.safeguard.dto.ReActThought;
import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.service.TokenCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
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

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final ToolRegistry toolRegistry;
    private final TokenCostService tokenCostService;
    private final LLMConfig llmConfig;

    public List<ReActThought> execute(String input, String context, String sessionId, String contextType) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        List<Tool> tools = toolRegistry.getTools(contextType);
        if (builder == null || tools.isEmpty()) {
            return List.of();
        }

        List<ToolObservation> observations = new ArrayList<>();
        List<ToolCallback> callbacks = tools.stream()
                .map(tool -> createCallback(tool, input, sessionId, observations))
                .toList();

        int maxTokens = llmConfig.getMaxTokens() == null ? 0 : llmConfig.getMaxTokens();
        TokenCostService.Reservation reservation = tokenCostService.reserve(
                "function-calling", llmConfig.getModel(), input + "\n" + safe(context), maxTokens * 3);

        try {
            String answer = builder.build()
                    .prompt()
                    .system("你是 SafeGuard 反诈骗分析助手。请按需调用工具获取证据，禁止虚构工具结果；完成后输出简洁、可执行的中文风险结论。")
                    .user("用户问题：\n" + input + "\n\n已检索上下文：\n" + safe(context))
                    .toolCallbacks(callbacks)
                    .call()
                    .content();

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
            tokenCostService.complete(reservation, null, null, false, "success");
            return thoughts;
        } catch (Exception exception) {
            tokenCostService.cancel(reservation, "error");
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
