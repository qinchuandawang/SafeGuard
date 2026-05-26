package com.sdu.safeguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioDetectionResponse {

    private String label;
    private Double spoofProb;
    private Double bonafideProb;
    private Double confidence;
    private String riskLevel;
    private Double latencyMs;
    private String modelVersion;
    private String device;
}
