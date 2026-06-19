package com.sdu.safeguard.dto;

import lombok.Data;

@Data
public class AudioDetectionResult {
    private String type = "audio";
    private String taskId;
    
    // 原始响应字段（来自音频训练模块）
    private String label;              // bonafide / spoof
    private Double spoofProb;           // 伪造概率
    private Double bonafideProb;        // 真实概率
    private Double confidence;          // 置信度
    private String riskLevel;          // low / medium / high
    private Double latencyMs;           // 检测耗时
    private String modelVersion;        // 模型版本
    private String device;              // 推理设备
    private String report;              // AI 生成的详细分析报告（纯文本）
    
    // 兼容旧格式
    private Double fakeProbability;
    private String fakeType;
    private String details;
    private Probabilities probabilities;
    
    @Data
    public static class Probabilities {
        private Double real;
        private Double fake;
    }
    
    public void computeProbabilities() {
        // 优先使用音频训练模块返回的 spoofProb
        if (spoofProb != null) {
            double fake = Math.max(0, Math.min(1, spoofProb));
            this.probabilities = new Probabilities();
            this.probabilities.setFake(fake);
            this.probabilities.setReal(1.0 - fake);
            this.fakeProbability = fake;
        } else if (fakeProbability != null) {
            double fake = Math.max(0, Math.min(1, fakeProbability));
            this.probabilities = new Probabilities();
            this.probabilities.setFake(fake);
            this.probabilities.setReal(1.0 - fake);
        }
        
        // 如果没有设置 confidence，使用 spoofProb 或 fakeProbability
        if (confidence == null && spoofProb != null) {
            this.confidence = spoofProb;
        }
    }
    
    /**
     * 从音频训练模块的响应构建结果
     */
    public static AudioDetectionResult fromTrainingModule(
            String label, Double spoofProb, Double bonafideProb,
            Double confidence, String riskLevel, Double latencyMs,
            String modelVersion, String device) {
        AudioDetectionResult result = new AudioDetectionResult();
        result.setType("audio");
        result.setLabel(label);
        result.setSpoofProb(spoofProb);
        result.setBonafideProb(bonafideProb);
        result.setConfidence(confidence);
        result.setRiskLevel(riskLevel);
        result.setLatencyMs(latencyMs);
        result.setModelVersion(modelVersion);
        result.setDevice(device);
        result.computeProbabilities();
        return result;
    }
}
