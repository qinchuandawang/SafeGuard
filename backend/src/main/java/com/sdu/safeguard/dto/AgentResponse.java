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
public class AgentResponse {
    private String agentType;
    private String result;
    private double confidence;
    private Map<String, Object> data;
    private List<String> actions;
    private String reasoning;
    private List<RagQueryResult> referencedKnowledge;
    private String status;
    private String error;
}
