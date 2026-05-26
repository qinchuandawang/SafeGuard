package com.sdu.safeguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorRequest {
    private String query;
    private String sessionId;
    private String userId;
    private String mode;
    private List<String> requiredAgents;
    private boolean useReAct;
    private boolean useCoT;
    private boolean useRAG;
}
