package com.sdu.safeguard.dto;

import lombok.Data;

import java.util.List;

@Data
public class VideoDetectionResult {
    private String type = "video";
    private Double fakeProbability;
    private Double confidence;
    private String determination;  // "fake" / "real" / "uncertain"
    private String fakeType;
    private List<FrameAnalysis> frameAnalysis;
    private Probabilities probabilities;
    private String report;  // AI 生成的详细分析报告（纯文本）

    @Data
    public static class FrameAnalysis {
        private Integer frameIndex;
        private Double fakeProbability;
    }

    @Data
    public static class Probabilities {
        private Double real;
        private Double fake;
    }

    public void computeProbabilities() {
        if (fakeProbability != null) {
            double fake = Math.max(0, Math.min(1, fakeProbability));
            this.probabilities = new Probabilities();
            this.probabilities.setFake(fake);
            this.probabilities.setReal(1.0 - fake);
        }
    }
}
