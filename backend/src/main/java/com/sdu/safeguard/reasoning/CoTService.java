package com.sdu.safeguard.reasoning;

import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.dto.CoTResult;
import com.sdu.safeguard.dto.RagQueryResult;
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
public class CoTService {

    private final LLMConfig llmConfig;
    private final PromptLoader promptLoader;
    private final RAGService ragService;
    private final RestTemplate restTemplate;

    public CoTResult analyzeWithCoT(String text) {
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

        List<RagQueryResult> ragResults = ragService.query(text);
        String knowledge = ragService.formatRagContext(ragResults);

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

                scamType = extractJsonField(jsonPart, "scamType", scamType);
                riskLevel = extractJsonField(jsonPart, "riskLevel", riskLevel);

                String probStr = extractJsonField(jsonPart, "riskProbability", "0.0");
                try {
                    riskProbability = Double.parseDouble(probStr);
                } catch (NumberFormatException ignored) {}

                advice = extractJsonField(jsonPart, "advice", "");

                String pointsStr = extractJsonField(jsonPart, "suspiciousPoints", "[]");
                if (pointsStr.startsWith("[")) {
                    pointsStr = pointsStr.substring(1, pointsStr.length() - 1);
                    for (String p : pointsStr.split(",")) {
                        String clean = p.replace("\"", "").trim();
                        if (!clean.isEmpty()) suspiciousPoints.add(clean);
                    }
                }

                String stepsStr = extractJsonField(jsonPart, "reasoningSteps", "[]");
                if (stepsStr.startsWith("[")) {
                    stepsStr = stepsStr.substring(1, stepsStr.length() - 1);
                    for (String s : stepsStr.split(",")) {
                        String clean = s.replace("\"", "").trim();
                        if (!clean.isEmpty()) steps.add(clean);
                    }
                }
            }

            String[] lines = llmOutput.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.matches(".*[Step步骤]\\s*\\d+.*") || trimmed.startsWith("-") || trimmed.startsWith("•")) {
                    if (!steps.contains(trimmed)) {
                        steps.add(trimmed);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("CoT结果解析失败: {}", e.getMessage());
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

    private String extractJsonField(String json, String field, String defaultVal) {
        String searchKey = "\"" + field + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return defaultVal;
        int colonIdx = json.indexOf(":", keyIdx + searchKey.length());
        if (colonIdx < 0) return defaultVal;
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return defaultVal;

        if (json.charAt(start) == '"') {
            int end = start + 1;
            while (end < json.length() && json.charAt(end) != '"') {
                if (json.charAt(end) == '\\') end++;
                end++;
            }
            return end < json.length() ? json.substring(start + 1, end) : defaultVal;
        } else if (json.charAt(start) == '[') {
            int end = start + 1;
            int depth = 1;
            while (end < json.length() && depth > 0) {
                if (json.charAt(end) == '[') depth++;
                if (json.charAt(end) == ']') depth--;
                end++;
            }
            return json.substring(start, Math.min(end, json.length()));
        } else {
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end))
                    || json.charAt(end) == '.')) end++;
            return json.substring(start, end);
        }
    }

    private String callLLM(String prompt) {
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
            return extractContent(response.getBody());
        } catch (Exception e) {
            log.error("CoT LLM调用失败: {}", e.getMessage());
            return "{\"riskLevel\":\"未知\",\"scamType\":\"分析失败\",\"riskProbability\":0.0}";
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
