package com.sdu.safeguard.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 文本检测结果。前端期望结构化字段而非 LLM 原始 JSON 字符串。
 * {@code report} 字段保存 LLM 的原始输出（如果解析失败时用作降级显示）。
 */
@Data
public class TextDetectionResult {
    private String type = "text";
    private String riskLevel;        // high / medium / low
    private Double riskProbability;  // 0-1
    private String scamType;
    private List<String> suspiciousPoints;
    private List<String> reasoningSteps;
    private List<String> advice;
    private String report;           // 原始 LLM 输出（解析失败时用）
    private Double confidence;       // 兼容字段，等同 riskProbability
    private Map<String, Double> probabilities;  // 兼容字段

    // ===== 知识增强证据层（RAG 融入核心检测的辅助信号） =====
    /** 规则过滤风险分（0~1），规则未命中时为 0 */
    private Double ruleRiskScore;
    /** 命中的欺诈规则关键词 */
    private List<String> matchedRules;
    /** 命中的反诈知识关键词（知识库证据） */
    private List<String> knowledgeEvidence;
    /** 是否由规则/RAG 辅助信号抬升了风险结论 */
    private Boolean ruleEscalated;

    public void computeProbabilities() {
        if (riskProbability != null) {
            double fake = Math.max(0, Math.min(1, riskProbability));
            this.probabilities = new java.util.LinkedHashMap<>();
            this.probabilities.put("fake", fake);
            this.probabilities.put("real", 1.0 - fake);
            this.confidence = fake;
        }
    }
}
