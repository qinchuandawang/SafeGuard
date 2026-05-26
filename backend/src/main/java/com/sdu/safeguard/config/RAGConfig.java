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
    private int embeddingDimension = 1024;
    private String qdrantHost = "localhost";
    private int qdrantPort = 6333;
    private String collectionName = "anti_fraud_knowledge";
    private int fixedChunkSize = 256;
    private int fixedChunkOverlap = 32;
    private int semanticChunkMinSize = 100;
    private int semanticChunkMaxSize = 512;
    private double semanticSimilarityThreshold = 0.75;
    private int hnswTopK = 20;
    private int finalTopK = 5;
    private double keywordBoostFactor = 1.2;

    @PostConstruct
    public void validate() {
        log.info("RAG配置加载完成: collection={}, dim={}", collectionName, embeddingDimension);
    }
}
