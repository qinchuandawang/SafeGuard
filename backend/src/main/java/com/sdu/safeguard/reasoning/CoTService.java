package com.sdu.safeguard.reasoning;

import com.sdu.safeguard.dto.CoTResult;
import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.rag.agentic.AgenticRAGService;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.util.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoTService {

    private final PromptLoader promptLoader;
    private final AgenticRAGService ragService;
    private final ObjectMapper objectMapper;
    private final LLMService llmService;

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
        try {
            return llmService.complete(prompt, "cot-analysis");
        } catch (Exception e) {
            log.error("CoT LLM调用失败: {}", e.getMessage());
            return "{\"riskLevel\":\"未知\",\"scamType\":\"分析失败\",\"riskProbability\":0.0}";
        }
    }
}
