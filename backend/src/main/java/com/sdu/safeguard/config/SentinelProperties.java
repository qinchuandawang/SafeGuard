package com.sdu.safeguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "infra.sentinel")
public class SentinelProperties {

    private boolean enabled = true;
    private String projectName = "SafeGuard";
    private String dashboard = "localhost:8858";
    private int apiPort = 8719;
    private Retry retry = new Retry();
    private Map<String, ResourceRule> resources = new LinkedHashMap<>();

    @Data
    public static class Retry {
        private int maxAttempts = 2;
        private long backoffMs = 300;
    }

    @Data
    public static class ResourceRule {
        private double maxConcurrent = 8;
        private double slowCallThresholdMs = 15_000;
        private double slowCallRatioThreshold = 0.5;
        private double exceptionRatioThreshold = 0.5;
        private int minimumRequestAmount = 5;
        private int statIntervalMs = 30_000;
        private int openDurationSeconds = 20;
    }
}
