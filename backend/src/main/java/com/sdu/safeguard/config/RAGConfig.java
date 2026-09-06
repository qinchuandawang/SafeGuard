package com.sdu.safeguard.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag")
@Data
@Slf4j
public class RAGConfig {
    private int embeddingDimension = 768;
    private String qdrantHost = "localhost";
    private int qdrantPort = 6333;
    private String collectionName = "anti_fraud_knowledge_v2";
    private int fixedChunkSize = 256;
    private int fixedChunkOverlap = 32;
    private int semanticChunkMinSize = 100;
    private int semanticChunkMaxSize = 512;
    private double semanticSimilarityThreshold = 0.75;
    private int hnswTopK = 20;
    private int rerankTopK = 10;
    private int finalTopK = 5;
    private double keywordBoostFactor = 1.2;
    private double dedupThreshold = 0.92;
    private boolean allowInMemoryFallback = false;
    private String rulesPath = "config/rules.json";

    // ===== Agentic RAG =====
    /** 是否启用 Agentic 检索（检索→反思→定向补检），关闭时退化为单轮 RAG */
    private boolean agenticEnabled = true;
    /** Agentic 检索最大迭代轮数（首轮 + 补检轮） */
    private int agenticMaxIterations = 2;
    /** 反思覆盖度阈值：查询关键词在返回块中的覆盖率低于该值则触发补检 */
    private double agenticCoverageThreshold = 0.6;
    /** 是否启用 LLM 反思判定（成本更高），默认用规则覆盖度自检 */
    private boolean agenticUseLlmReflection = false;

    // ===== 知识增强风险混合（融入核心检测） =====
    /** 文本检测在 LLM 概率不确定时，是否用规则/RAG 风险分做辅助抬升 */
    private boolean riskBlendEnabled = true;
    /** 辅助抬升权重：blended = max(p, ruleRiskScore * weight) */
    private double riskBlendWeight = 0.75;
    /** 触发抬升所需的最低规则风险分 */
    private double riskBlendRuleFloor = 0.85;
    /** 触发抬升所需的概率不确定区间半径（|p - 0.5| 小于该值） */
    private double riskBlendUncertaintyBand = 0.15;

    // ===== LLM 回复语义缓存 =====
    /** 语义缓存余弦相似度阈值：语义相近的重复查询复用缓存答案，阈值需偏高避免误复用 */
    private double llmSemanticCacheThreshold = 0.70;

    @PostConstruct
    public void validate() {
        log.info("RAG配置加载完成: collection={}, dim={}, rerankTopK={}, dedupThreshold={}, allowInMemoryFallback={}, " +
                        "agenticEnabled={}, agenticMaxIterations={}, riskBlendEnabled={}",
                collectionName, embeddingDimension, rerankTopK, dedupThreshold, allowInMemoryFallback,
                agenticEnabled, agenticMaxIterations, riskBlendEnabled);
    }
}
