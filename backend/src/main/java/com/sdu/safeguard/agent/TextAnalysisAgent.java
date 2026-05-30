package com.sdu.safeguard.agent;

import com.sdu.safeguard.dto.AgentRequest;
import com.sdu.safeguard.dto.AgentResponse;
import com.sdu.safeguard.dto.CoTResult;
import com.sdu.safeguard.reasoning.CoTService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TextAnalysisAgent implements Agent {

    private final CoTService cotService;
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

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scamType", cotResult.getScamType());
            data.put("riskLevel", cotResult.getRiskLevel());
            data.put("riskProbability", cotResult.getRiskProbability());
            data.put("suspiciousPoints", cotResult.getSuspiciousPoints());
            data.put("advice", cotResult.getAdvice());
            data.put("reasoningSteps", cotResult.getReasoningSteps());

            String analysisJson = objectMapper.writeValueAsString(data);

            return AgentResponse.builder()
                    .agentType("TEXT_ANALYSIS")
                    .result(analysisJson)
                    .confidence(cotResult.getRiskProbability())
                    .data(data)
                    .actions(List.of("ANALYZE_TEXT", "COT_REASONING", "KNOWLEDGE_RETRIEVAL"))
                    .reasoning(String.join("\n", cotResult.getReasoningSteps()))
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
}