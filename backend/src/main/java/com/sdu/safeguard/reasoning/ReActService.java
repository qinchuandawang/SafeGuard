package com.sdu.safeguard.reasoning;

import com.sdu.safeguard.agent.tool.Tool;
import com.sdu.safeguard.agent.tool.ToolExecutionRequest;
import com.sdu.safeguard.agent.tool.ToolRegistry;
import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.dto.ReActThought;
import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.memory.MemoryService;
import com.sdu.safeguard.rag.RAGService;
import com.sdu.safeguard.util.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReActService {

    private static final int MAX_STEPS = 5;

    private final LLMConfig llmConfig;
    private final PromptLoader promptLoader;
    private final RAGService ragService;
    private final MemoryService memoryService;
    private final RestTemplate restTemplate;
    private final ToolRegistry toolRegistry;

    public List<ReActThought> executeReAct(String input, String sessionId) {
        return executeReAct(input, sessionId, null, null);
    }

    public List<ReActThought> executeReAct(String input, String sessionId, String externalKnowledge) {
        return executeReAct(input, sessionId, externalKnowledge, null);
    }

    /**
     * 外部传入 RAG context 和上下文类型的版本。
     */
    public List<ReActThought> executeReAct(String input, String sessionId, String externalKnowledge, String contextType) {
        List<ReActThought> thoughts = new ArrayList<>();

        List<RagQueryResult> ragResults;
        String knowledge;
        if (externalKnowledge != null && !externalKnowledge.isBlank()) {
            knowledge = externalKnowledge;
            ragResults = new ArrayList<>();
        } else {
            ragResults = ragService.query(input);
            knowledge = ragService.formatRagContext(ragResults);
        }
        String memoryContext = memoryService.formatMemoryContext(sessionId, input);
        String toolDescriptions = toolRegistry.buildToolDescriptions(contextType);

        String currentInput = input;
        String currentContext = knowledge + "\n" + memoryContext;

        for (int step = 0; step < MAX_STEPS; step++) {
            log.debug("ReAct Step {}/{}", step + 1, MAX_STEPS);

            // 1. 思考 — LLM 选择工具
            String thought = think(currentInput, currentContext, toolDescriptions, step);
            if (thought == null) break;

            // 2. 从 LLM 输出解析 Action 和 Action Input
            String action = parseAction(thought);
            String actionInput = extractActionInput(thought);

            log.debug("ReAct Step {}: action={}, actionInput={}", step + 1, action, actionInput);

            // 3. 执行工具
            String observation = executeTool(action, actionInput, currentInput, ragResults, sessionId, thought);

            // 4. 判断是否结束
            boolean isFinal = action.equals("FINISH") || step == MAX_STEPS - 1
                    || (thought.toLowerCase().contains("结论") && action.equals("ANALYZE"));

            ReActThought reactThought = ReActThought.builder()
                    .step(step + 1)
                    .thought(thought)
                    .action(action)
                    .actionInput(actionInput)
                    .observation(observation)
                    .isFinal(isFinal)
                    .finalAnswer(isFinal ? observation : null)
                    .referencedSources(ragResults.stream()
                            .map(RagQueryResult::getSource).distinct().limit(3).toList())
                    .build();

            thoughts.add(reactThought);

            if (isFinal) break;

            currentInput = "问题: " + input + "\n刚才的思考: " + thought + "\n观察: " + observation;
        }

        memoryService.addShortTerm(sessionId, "system",
                "ReAct分析结果: " + (thoughts.isEmpty() ? "无结果" : thoughts.get(thoughts.size() - 1).getFinalAnswer()),
                List.of("react", "analysis"));

        log.info("ReAct完成: {} steps", thoughts.size());
        return thoughts;
    }

    /**
     * 从 LLM 输出中解析 Action 字段。
     * 先尝试找 "Action: XXXX" 格式，再回退到关键字匹配。
     */
    private String parseAction(String thought) {
        if (thought == null) return "FINISH";

        // 优先解析结构化输出 Action: XXX
        for (String line : thought.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Action:") && trimmed.length() > 7) {
                String action = trimmed.substring(7).trim();
                if (!action.isEmpty()) {
                    // 如果工具注册表中有此工具，直接返回
                    if (toolRegistry.hasTool(action)) {
                        return action;
                    }
                    // FINISH 特殊处理
                    if (action.equalsIgnoreCase("FINISH") || action.equalsIgnoreCase("结束")) {
                        return "FINISH";
                    }
                    // 其他未注册的工具 → 回退到关键字匹配
                    log.debug("未注册的工具: {}, 回退到关键字匹配", action);
                }
            }
        }

        // 回退: 关键字匹配
        String lower = thought.toLowerCase();
        if (lower.contains("搜索") || lower.contains("查询") || lower.contains("知识库")
                || lower.contains("search") || lower.contains("rag")) {
            return "SEARCH_KNOWLEDGE";
        }
        if (lower.contains("音频") || lower.contains("声音") || lower.contains("语音")) {
            return toolRegistry.hasTool("DETECT_AUDIO") ? "DETECT_AUDIO" : "ANALYZE";
        }
        if (lower.contains("视频") || lower.contains("画面") || lower.contains("录像")) {
            return toolRegistry.hasTool("DETECT_VIDEO") ? "DETECT_VIDEO" : "ANALYZE";
        }
        if (lower.contains("结论") || lower.contains("最终") || lower.contains("回答")
                || lower.contains("final") || lower.contains("finish")) {
            return "FINISH";
        }
        return "ANALYZE";
    }

    private String extractActionInput(String thought) {
        if (thought == null) return "";
        for (String line : thought.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Action Input:") && trimmed.length() > 13) {
                return trimmed.substring(13).trim();
            }
        }
        return "";
    }

    private String think(String input, String context, String toolDescriptions, int step) {
        String prompt = promptLoader.loadPrompt("react_thought", Map.of(
                "input", input,
                "context", context,
                "toolDescriptions", toolDescriptions,
                "step", String.valueOf(step + 1),
                "maxSteps", String.valueOf(MAX_STEPS)
        ));
        return callLLM(prompt);
    }

    /**
     * 执行工具：从 ToolRegistry 查找并调用。
     * 如果工具不存在，fallback 到 LLM 分析或结束。
     */
    private String executeTool(String action, String actionInput,
                                String originalInput, List<RagQueryResult> ragResults,
                                String sessionId, String thought) {
        // 1. 从注册表查找工具
        Tool tool = toolRegistry.getTool(action);

        if (tool != null) {
            // 工具存在 → 真正执行
            log.debug("执行工具: {}", action);
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .toolName(action)
                    .input(actionInput.isEmpty() ? originalInput : actionInput)
                    .parameters(Map.of(
                            "sessionId", sessionId,
                            "originalInput", originalInput,
                            "thought", thought
                    ))
                    .build();
            var result = tool.execute(request);
            if (result.isSuccess()) {
                return result.getOutput();
            }
            return "工具执行失败: " + result.getError();
        }

        // 2. FINISH → 生成最终结论
        if ("FINISH".equals(action)) {
            String prompt = promptLoader.loadPrompt("react_final", Map.of(
                    "input", originalInput,
                    "analysis", actionInput.isEmpty() ? "综合以上分析" : actionInput
            ));
            return callLLM(prompt);
        }

        // 3. ANALYZE 或未识别 → fallback LLM 分析
        String prompt = promptLoader.loadPrompt("react_analysis", Map.of(
                "input", originalInput,
                "thought", actionInput.isEmpty() ? thought : actionInput,
                "knowledge", ragService.formatRagContext(ragResults)
        ));
        return callLLM(prompt);
    }

    public String getFinalAnswer(List<ReActThought> thoughts) {
        if (thoughts == null || thoughts.isEmpty()) return "";
        ReActThought last = thoughts.get(thoughts.size() - 1);
        return last.getFinalAnswer() != null ? last.getFinalAnswer() : last.getObservation();
    }

    private String callLLM(String prompt) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", llmConfig.getModel());

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "你是一个反诈骗分析助手，可以根据需要调用工具获得外部信息。\n" +
                "请严格按照以下格式输出：\n" +
                "Thought: 你的推理过程\n" +
                "Action: 你选择的工具名称\n" +
                "Action Input: 工具的参数（如有）\n" +
                "如果已经收集足够信息，请输出 Action: FINISH"));
        messages.add(Map.of("role", "user", "content", prompt));
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.5);
        requestBody.put("max_tokens", 1024);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmConfig.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    llmConfig.getApiUrl(),
                    HttpMethod.POST,
                    entity,
                    Object.class);
            return extractContent(response.getBody());
        } catch (Exception e) {
            log.error("ReAct LLM调用失败: {}", e.getMessage());
            return "分析过程暂不可用: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?> body)) return "";
        Object choicesObj = body.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return "";
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choice)) return "";
        Object messageObj = choice.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) return "";
        Object content = message.get("content");
        return content instanceof String ? (String) content : "";
    }
}