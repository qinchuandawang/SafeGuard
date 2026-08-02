package com.sdu.safeguard.agent;

import com.sdu.safeguard.dto.AgentRequest;
import com.sdu.safeguard.dto.AgentResponse;
import com.sdu.safeguard.dto.CoTResult;
import com.sdu.safeguard.dto.TextDetectionResult;
import com.sdu.safeguard.reasoning.CoTService;
import com.sdu.safeguard.service.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TextAnalysisAgent implements Agent {

    private final CoTService cotService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "TEXT_ANALYSIS";
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        try {
            CoTResult cotResult = null;
            if (request.getContext() != null && request.getContext().get("cotResult") instanceof CoTResult ctx) {
                cotResult = ctx;
            }
            if (cotResult == null) {
                cotResult = cotService.analyzeWithCoT(request.getInput());
            }
            TextDetectionResult llmResult = llmService.analyzeTextStructured(request.getInput());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "text");
            data.put("scamType", valueOrDefault(llmResult.getScamType(), cotResult.getScamType()));
            data.put("riskLevel", valueOrDefault(llmResult.getRiskLevel(), cotResult.getRiskLevel()));
            data.put("riskProbability", llmResult.getRiskProbability() != null
                    ? llmResult.getRiskProbability()
                    : cotResult.getRiskProbability());
            data.put("confidence", llmResult.getConfidence() != null
                    ? llmResult.getConfidence()
                    : cotResult.getRiskProbability());
            data.put("probabilities", llmResult.getProbabilities());
            data.put("suspiciousPoints", nonEmptyList(llmResult.getSuspiciousPoints(), cotResult.getSuspiciousPoints()));
            data.put("features", nonEmptyList(llmResult.getSuspiciousPoints(), cotResult.getSuspiciousPoints()));
            data.put("advice", nonEmptyList(llmResult.getAdvice(), cotResult.getAdvice() == null ? List.of() : List.of(cotResult.getAdvice())));
            data.put("reasoningSteps", nonEmptyList(llmResult.getReasoningSteps(), cotResult.getReasoningSteps()));
            data.put("report", valueOrDefault(llmResult.getReport(), objectMapper.writeValueAsString(llmResult)));
            data.put("source", "deepseek-agent-orchestrator");
            data.put("agentSteps", List.of(
                    Map.of("name", "DeepSeek 中枢分析", "status", "completed", "description", "由大模型理解文本语义并输出结构化风险判断"),
                    Map.of("name", "CoT 推理校验", "status", "completed", "description", "用可展示的分步推理校验诈骗链路"),
                    Map.of("name", "RAG 知识增强", "status", "completed", "description", "结合反诈知识库补充依据和建议")
            ));

            String analysisJson = objectMapper.writeValueAsString(data);

            return AgentResponse.builder()
                    .agentType("TEXT_ANALYSIS")
                    .result(analysisJson)
                    .confidence(asDouble(data.get("confidence")))
                    .data(data)
                    .actions(List.of("DEEPSEEK_TEXT_ANALYSIS", "COT_REASONING", "KNOWLEDGE_RETRIEVAL"))
                    .reasoning(String.join("\n", nonEmptyList(llmResult.getReasoningSteps(), cotResult.getReasoningSteps())))
                    .status("SUCCESS")
                    .build();
        } catch (Exception e) {
            log.error("TextAnalysisAgent执行失败", e);
            return AgentResponse.builder()
                    .agentType("TEXT_ANALYSIS")
                    .status("FAILED")
                    .error(e.getMessage())
                    .build();
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private List<String> nonEmptyList(List<String> value, List<String> fallback) {
        return value != null && !value.isEmpty() ? value : (fallback != null ? fallback : List.of());
    }

    private double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
