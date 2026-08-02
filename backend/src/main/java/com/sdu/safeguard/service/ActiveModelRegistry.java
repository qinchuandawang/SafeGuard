package com.sdu.safeguard.service;

import com.sdu.safeguard.config.ModelCatalogProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class ActiveModelRegistry {

    private final ModelCatalogProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final AtomicReference<String> activeAudioModel = new AtomicReference<>();
    private final AtomicReference<String> activeVideoModel = new AtomicReference<>();

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    @PostConstruct
    public void init() {
        activeAudioModel.set(readRedis("audio").orElse(properties.getDefaultAudioModel()));
        activeVideoModel.set(readRedis("video").orElse(properties.getDefaultVideoModel()));
    }

    public String getActiveAudioModel() {
        return readRedis("audio").orElse(activeAudioModel.get());
    }

    public String getActiveVideoModel() {
        return readRedis("video").orElse(activeVideoModel.get());
    }

    public void switchModel(String category, String modelId) {
        List<ModelCatalogProperties.ModelSpec> models = "video".equalsIgnoreCase(category)
                ? properties.getVideo()
                : properties.getAudio();
        boolean exists = models.stream().anyMatch(model -> modelId.equals(model.getId()) && model.isEnabled());
        if (!exists) {
            throw new IllegalArgumentException("模型不存在或权重/适配器尚未就绪: " + modelId);
        }
        if ("video".equalsIgnoreCase(category)) {
            activeVideoModel.set(modelId);
            writeRedis("video", modelId);
        } else {
            activeAudioModel.set(modelId);
            writeRedis("audio", modelId);
        }
    }

    private Optional<String> readRedis(String category) {
        if (!redisEnabled) {
            return Optional.empty();
        }
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            return redisTemplate == null ? Optional.empty()
                    : Optional.ofNullable(redisTemplate.opsForValue().get(redisKey(category)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private void writeRedis(String category, String modelId) {
        if (!redisEnabled) {
            return;
        }
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                redisTemplate.opsForValue().set(redisKey(category), modelId);
            }
        } catch (Exception ignored) {
            // Redis 故障时保留本机状态，不阻断模型切换。
        }
    }

    private String redisKey(String category) {
        return "safeguard:model:active:" + category;
    }

    public Optional<ModelCatalogProperties.ModelSpec> findActiveAudioModel() {
        return properties.getAudio().stream()
                .filter(model -> getActiveAudioModel().equals(model.getId()))
                .findFirst();
    }

    public Optional<ModelCatalogProperties.ModelSpec> findActiveVideoModel() {
        return properties.getVideo().stream()
                .filter(model -> getActiveVideoModel().equals(model.getId()))
                .findFirst();
    }
}
