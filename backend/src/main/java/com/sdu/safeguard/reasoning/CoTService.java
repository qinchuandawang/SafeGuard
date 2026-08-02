package com.sdu.safeguard.reasoning;

import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.dto.CoTResult;
import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.rag.RAGService;
import com.sdu.safeguard.service.TokenCostService;
import com.sdu.safeguard.util.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoTService {

    private final LLMConfig llmConfig;
    private final PromptLoader promptLoader;
    private final RAGService ragService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TokenCostService tokenCostService;

    public CoTResult analyzeWithCoT(String text) {
        return analyzeWithCoT(text, null);
    }

    /**
     * 支持外部传入 RAG context 的重载版本。
     * @param text  待分析文本
     * @param externalKnowledge 外部 RAG context（如果为 null 则内部自动查询）
     */
    public CoTResult analyzeWithCoT(String text, String externalKnowledge) {
        long startTime = System.currentTimeMillis();

        if (text == null || text.isBlank()) {
            CoTResult empty = CoTResult.builder()
                    .originalInput(text)
                    .scamType("分析失败")
                    .riskLevel("未知")
                    .riskProbability(0.0)
                    .suspiciousPoints(List.of())
                    .advice("")
                    .reasoningSteps(List.of("输入为空"))
                    .build();
            log.info("CoT分析跳过: 输入为空");
            return empty;
        }

        String knowledge;
        if (externalKnowledge != null && !externalKnowledge.isBlank()) {
            // 使用外部传入的 RAG context（避免 Orchestrator 层面的冗余 RAG 调用）
            knowledge = externalKnowledge;
        } else {
            List<RagQueryResult> ragResults = ragService.query(text);
            knowledge = ragService.formatRagContext(ragResults);
        }

        String prompt = promptLoader.loadPrompt("cot_analysis", Map.of(
                "text", text,
                "knowledge", knowledge
        ));

        String llmResult = callLLM(prompt);

        CoTResult result = parseCoTResult(llmResult, text);

        long cost = System.currentTimeMillis() - startTime;
        log.info("CoT分析完成: risk={}, cost={}ms", result.getRiskLevel(), cost);

        return result;
    }

    @SuppressWarnings("unchecked")
    CoTResult parseCoTResult(String llmOutput, String originalInput) {
        CoTResult.CoTResultBuilder builder = CoTResult.builder()
                .originalInput(originalInput);

        List<String> steps = new ArrayList<>();
        String scamType = "未知";
        String riskLevel = "低";
        double riskProbability = 0.0;
        List<String> suspiciousPoints = new ArrayList<>();
        String advice = "";

        try {
            int jsonStart = llmOutput.indexOf("{");
            int jsonEnd = llmOutput.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonPart = llmOutput.substring(jsonStart, jsonEnd + 1);
                Map<String, Object> parsed = objectMapper.readValue(jsonPart,
                        new TypeReference<Map<String, Object>>() {});

                if (parsed.containsKey("scamType")) scamType = parsed.get("scamType").toString();
                if (parsed.containsKey("riskLevel")) riskLevel = parsed.get("riskLevel").toString();
                if (parsed.containsKey("riskProbability")) {
                    try {
                        riskProbability = Double.parseDouble(parsed.get("riskProbability").toString());
                    } catch (NumberFormatException ignored) {}
                }
                if (parsed.containsKey("advice")) advice = parsed.get("advice").toString();
                if (parsed.containsKey("suspiciousPoints") && parsed.get("suspiciousPoints") instanceof List<?> rawPoints) {
                    for (Object p : rawPoints) {
                        if (p != null) suspiciousPoints.add(p.toString());
                    }
                }
                if (parsed.containsKey("reasoningSteps") && parsed.get("reasoningSteps") instanceof List<?> rawSteps) {
                    for (Object s : rawSteps) {
                        if (s != null) steps.add(s.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("CoT JSON解析失败，回退到行解析: {}", e.getMessage());
            String[] lines = llmOutput.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.matches(".*[Step步骤]\\s*\\d+.*") || trimmed.startsWith("-") || trimmed.startsWith("•")) {
                    if (!steps.contains(trimmed)) steps.add(trimmed);
                }
            }
        }

        if (steps.isEmpty()) {
            steps.add("Step1: 提取关键信息 - 识别文本中的诈骗关键词");
            steps.add("Step2: 模式匹配 - 对比已知诈骗话术模式");
            steps.add("Step3: 风险评估 - 综合判断风险等级");
            steps.add("Step4: 给出建议 - 生成防范建议");
        }

        return builder
                .reasoningSteps(steps)
                .scamType(scamType)
                .riskLevel(riskLevel)
                .riskProbability(riskProbability)
                .suspiciousPoints(suspiciousPoints)
                .advice(advice)
                .build();
    }

    private String callLLM(String prompt) {
        int maxTokens = llmConfig.getMaxTokens() == null ? 0 : llmConfig.getMaxTokens();
        TokenCostService.Reservation reservation = tokenCostService.reserve(
                "cot-analysis", llmConfig.getModel(), prompt, maxTokens);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmConfig.getApiKey());

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", llmConfig.getModel());
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", llmConfig.getMaxTokens());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    llmConfig.getApiUrl(),
                    HttpMethod.POST,
                    entity,
                    Object.class);
            Object body = response.getBody();
            String content = extractContent(body);
            int[] usage = extractUsage(body);
            tokenCostService.complete(reservation, usage[0], usage[1], false, "success");
            return content;
        } catch (Exception e) {
            tokenCostService.cancel(reservation, "error");
            log.error("CoT LLM调用失败: {}", e.getMessage());
            return "{\"riskLevel\":\"未知\",\"scamType\":\"分析失败\",\"riskProbability\":0.0}";
        }
    }

    private int[] extractUsage(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?> body) || !(body.get("usage") instanceof Map<?, ?> usage)) {
            return new int[]{-1, -1};
        }
        Object prompt = usage.get("prompt_tokens");
        Object completion = usage.get("completion_tokens");
        return new int[]{prompt instanceof Number number ? number.intValue() : -1,
                completion instanceof Number number ? number.intValue() : -1};
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
