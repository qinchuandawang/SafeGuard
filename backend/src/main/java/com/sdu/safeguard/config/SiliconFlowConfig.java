package com.sdu.safeguard.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "siliconflow")
@Data
@Slf4j
public class SiliconFlowConfig {
    private String apiKey = "";
    private String embeddingUrl = "https://api.siliconflow.cn/v1/embeddings";
    private String rerankUrl = "https://api.siliconflow.cn/v1/rerank";
    private String embeddingModel = "BAAI/bge-base-zh-v1.5";
    private String rerankModel = "BAAI/bge-reranker-v2-m3";

    @PostConstruct
    public void validate() {
        if (apiKey == null || apiKey.isBlank() || "<your-siliconflow-api-key>".equals(apiKey)) {
            log.warn("SiliconFlow API Key 未配置，嵌入服务将使用回退方案");
        } else {
            log.info("SiliconFlow 配置加载完成: model={}", embeddingModel);
        }
    }
}
