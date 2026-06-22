package com.sdu.safeguard.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm")
@Data
@Slf4j
public class LLMConfig {
    private String apiKey;
    private String apiUrl;
    private String model;
    private Double temperature;
    private Integer maxTokens;

    @PostConstruct
    public void validate() {
        if ("deepseek-v4-pro".equalsIgnoreCase(model)) {
            model = "DeepSeek-V4-Pro";
        } else if ("deepseek-v4-flash".equalsIgnoreCase(model)) {
            model = "DeepSeek-V4-Flash";
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM API Key 未配置");
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            log.warn("LLM API URL 未配置");
        }
        if (model == null || model.isBlank()) {
            log.warn("LLM model 未配置");
        } else {
            log.info("LLM 配置加载完成: model={}", model);
        }
    }
}
