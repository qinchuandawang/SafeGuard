package com.sdu.safeguard.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "memory")
@Data
@Slf4j
public class MemoryConfig {
    private int shortTermMaxSize = 50;
    private int longTermMaxSize = 1000;
    private long longTermTtlHours = 168;
    private double longTermImportanceThreshold = 0.6;
    private String storagePath = "./data/memory";
    private int memoryRetrievalTopK = 10;
    private String memoryCollection = "user_memories";
    private int memoryEmbeddingDim = 1024;

    @PostConstruct
    public void validate() {
        log.info("Memory配置: STM={}, LTM={}, 记忆集合={}, dim={}",
                shortTermMaxSize, longTermMaxSize, memoryCollection, memoryEmbeddingDim);
    }
}