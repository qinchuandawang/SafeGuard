package com.sdu.safeguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorResponse {
    private String finalResult;
    private Map<String, AgentResponse> agentResults;
    private List<ReActThought> reactThoughts;
    private CoTResult cotResult;
    private List<RagQueryResult> ragContext;
    private List<String> reasoningSummary;
    private long processingTimeMs;
}
