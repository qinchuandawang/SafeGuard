package com.sdu.safeguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "llm.cost-control")
public class TokenCostProperties {
    private boolean enabled = true;
    private long dailyTokenBudget = 1_000_000;
    private int maxPromptTokens = 8_000;
    private boolean responseCacheEnabled = true;
    private String promptVersion = "v1";
    private Set<String> cacheableScenes = new LinkedHashSet<>(Set.of(
            "text-analysis", "audio-report", "video-report", "multimodal"));
}
