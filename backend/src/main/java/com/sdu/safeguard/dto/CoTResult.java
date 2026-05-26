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
public class CoTResult {
    private String originalInput;
    private List<String> reasoningSteps;
    private String finalAnswer;
    private String scamType;
    private String riskLevel;
    private double riskProbability;
    private List<String> suspiciousPoints;
    private String advice;
}
