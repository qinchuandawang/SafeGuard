package com.sdu.safeguard.reasoning;

import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.dto.ReActThought;
import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.memory.MemoryService;
import com.sdu.safeguard.rag.RAGService;
import com.sdu.safeguard.util.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    public List<ReActThought> executeReAct(String input, String sessionId) {
        List<ReActThought> thoughts = new ArrayList<>();

        List<RagQueryResult> ragResults = ragService.query(input);
        String knowledge = ragService.formatRagContext(ragResults);

        String memoryContext = memoryService.formatMemoryContext(sessionId, input);

        String currentInput = input;
        String currentContext = knowledge + "\n" + memoryContext;

        for (int step = 0; step < MAX_STEPS; step++) {
            log.debug("ReAct Step {}/{}", step + 1, MAX_STEPS);

            String thought = think(currentInput, currentContext, step);
            if (thought == null) break;

            String action = decideAction(thought, currentInput);
            String actionInput = extractActionInput(thought);

            String observation = executeAction(action, actionInput, currentInput, ragResults);

            boolean isFinal = action.equals("FINISH") || step == MAX_STEPS - 1;

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
                "ReAct分析结果: " + (thoughts.isEmpty() ? "无结果" : thoughts.get(thoughts.size()-1).getFinalAnswer()),
                List.of("react", "analysis"));

        log.info("ReAct完成: {} steps", thoughts.size());
        return thoughts;
    }

    private String think(String input, String context, int step) {
        String prompt = promptLoader.loadPrompt("react_thought", Map.of(
                "input", input,
                "context", context,
                "step", String.valueOf(step + 1),
                "maxSteps", String.valueOf(MAX_STEPS)
        ));

        return callLLM(prompt);
    }

    private String decideAction(String thought, String input) {
        if (thought == null) return "FINISH";

        String lower = thought.toLowerCase();

        if (lower.contains("搜索") || lower.contains("查询") || lower.contains("知识库")
                || lower.contains("search") || lower.contains("rag")) {
            return "SEARCH_KNOWLEDGE";
        }
        if (lower.contains("分析") || lower.contains("判断") || lower.contains("评估")
                || lower.contains("analyze") || lower.contains("assess")) {
            return "ANALYZE";
        }
        if (lower.contains("结论") || lower.contains("最终") || lower.contains("回答")
                || lower.contains("final") || lower.contains("answer")) {
            return "FINISH";
        }
        return "ANALYZE";
    }

    private String extractActionInput(String thought) {
        if (thought == null) return "";
        int inputIdx = thought.indexOf("Action Input:");
        if (inputIdx >= 0) {
            return thought.substring(inputIdx + "Action Input:".length()).trim();
        }
        return thought;
    }

    private String executeAction(String action, String actionInput,
                                  String originalInput, List<RagQueryResult> ragResults) {
        return switch (action) {
            case "SEARCH_KNOWLEDGE" -> {
                String query = actionInput.isEmpty() ? originalInput : actionInput;
                List<RagQueryResult> results = ragService.query(query);
                yield ragService.formatRagContext(results);
            }
            case "ANALYZE" -> {
                String prompt = promptLoader.loadPrompt("react_analysis", Map.of(
                        "input", originalInput,
                        "thought", actionInput.isEmpty() ? "进行分析" : actionInput,
                        "knowledge", ragService.formatRagContext(ragResults)
                ));
                yield callLLM(prompt);
            }
            case "FINISH" -> {
                String prompt = promptLoader.loadPrompt("react_final", Map.of(
                        "input", originalInput,
                        "analysis", actionInput.isEmpty() ? "综合以上分析" : actionInput
                ));
                yield callLLM(prompt);
            }
            default -> "未识别的行动: " + action;
        };
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
                "你是一个反诈骗分析助手。请按照以下格式输出：\n" +
                "Thought: 你的推理过程\n" +
                "Action: SEARCH_KNOWLEDGE/ANALYZE/FINISH\n" +
                "Action Input: 行动的具体输入\n" +
                "Observation: 观察到的结果"));
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
