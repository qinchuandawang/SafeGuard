package com.sdu.safeguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "task.inference-capacity")
public class InferenceCapacityProperties {

    private boolean enabled = true;
    private long waitTimeMs = 1000;
    private int defaultAudioPermits = 8;
    private int defaultVideoPermits = 4;
    private long defaultAudioLeaseMs = 60_000;
    private long defaultVideoLeaseMs = 300_000;
    private Map<String, ModelCapacity> models = new LinkedHashMap<>();

    public Capacity resolve(String category, String modelId) {
        ModelCapacity override = models.get(modelId);
        boolean video = "video".equalsIgnoreCase(category);
        int defaultPermits = video ? defaultVideoPermits : defaultAudioPermits;
        long defaultLeaseMs = video ? defaultVideoLeaseMs : defaultAudioLeaseMs;
        int permits = override != null && override.getPermits() != null
                ? override.getPermits() : defaultPermits;
        long leaseMs = override != null && override.getLeaseMs() != null
                ? override.getLeaseMs() : defaultLeaseMs;
        if (permits <= 0 || leaseMs < 1000 || waitTimeMs < 0) {
            throw new IllegalStateException("推理容量配置必须满足 permits > 0、leaseMs >= 1000、waitTimeMs >= 0");
        }
        return new Capacity(permits, leaseMs, waitTimeMs);
    }

    public record Capacity(int permits, long leaseMs, long waitTimeMs) {
    }

    @Data
    public static class ModelCapacity {
        private String category;
        private Integer permits;
        private Long leaseMs;
    }
}
