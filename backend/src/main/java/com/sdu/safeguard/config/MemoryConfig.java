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

    @PostConstruct
    public void validate() {
        log.info("Memory配置加载完成: shortTerm={}, longTermMax={}, ttl={}h",
                shortTermMaxSize, longTermMaxSize, longTermTtlHours);
    }
}
