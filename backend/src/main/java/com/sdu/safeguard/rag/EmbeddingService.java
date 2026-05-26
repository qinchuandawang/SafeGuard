package com.sdu.safeguard.rag;

import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.RAGConfig;
import com.sdu.safeguard.config.SiliconFlowConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class EmbeddingService {

    private final RestTemplate restTemplate;
    private final RAGConfig ragConfig;
    private final SiliconFlowConfig siliconFlowConfig;
    private final Cache<String, List<Float>> embeddingCache;
    /** 避免重复打印 API Key 未配置的警告 */
    private boolean apiKeyWarned = false;

    public EmbeddingService(RestTemplate restTemplate,
                            RAGConfig ragConfig,
                            SiliconFlowConfig siliconFlowConfig,
                            @Qualifier("embeddingCache") Cache<String, List<Float>> embeddingCache) {
        this.restTemplate = restTemplate;
        this.ragConfig = ragConfig;
        this.siliconFlowConfig = siliconFlowConfig;
        this.embeddingCache = embeddingCache;
    }

    public List<Float> getEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return fallbackEmbedding(text != null ? text : "");
        }

        // Caffeine 缓存命中直接返回
        List<Float> cached = embeddingCache.getIfPresent(text);
        if (cached != null) {
            return cached;
        }

        List<Float> vector = callEmbeddingAPI(text);
        embeddingCache.put(text, vector);
        return vector;
    }

    private List<Float> callEmbeddingAPI(String text) {
        String apiKey = siliconFlowConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank() || "<your-siliconflow-api-key>".equals(apiKey)) {
            if (!apiKeyWarned) {
                log.warn("SiliconFlow API Key 未配置，使用回退嵌入（仅警告一次）");
                apiKeyWarned = true;
            }
            return fallbackEmbedding(text);
        }

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", siliconFlowConfig.getEmbeddingModel());
            request.put("input", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    siliconFlowConfig.getEmbeddingUrl(), entity, Map.class);

            if (response != null && response.containsKey("data")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                if (!data.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Double> embedding = (List<Double>) data.get(0).get("embedding");
                    return convertToFloat(embedding);
                }
            }
            log.warn("嵌入服务返回异常: {}", response);
            return fallbackEmbedding(text);
        } catch (Exception e) {
            log.error("嵌入服务调用失败, 使用回退嵌入: {}", e.getMessage());
            return fallbackEmbedding(text);
        }
    }

    public List<List<Float>> batchGetEmbedding(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<List<Float>> results = new ArrayList<>();
        for (String text : texts) {
            results.add(getEmbedding(text));
        }
        return results;
    }

    private List<Float> fallbackEmbedding(String text) {
        int dim = ragConfig.getEmbeddingDimension();
        List<Float> vec = new ArrayList<>(dim);
        long hash = 0;
        int prime = 31;
        for (int i = 0; i < text.length(); i++) {
            hash = hash * prime + text.charAt(i);
        }
        Random rng = new Random(hash);
        double norm = 0.0;
        float[] values = new float[dim];
        for (int i = 0; i < dim; i++) {
            values[i] = (float) (rng.nextGaussian() * 0.1);
            norm += values[i] * values[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < dim; i++) {
            vec.add(norm > 0 ? (float) (values[i] / norm) : 0.0f);
        }
        log.debug("回退嵌入生成: dim={}, textLen={}", dim, text.length());
        return vec;
    }

    private List<Float> convertToFloat(List<Double> doubles) {
        List<Float> result = new ArrayList<>(doubles.size());
        for (Double d : doubles) {
            result.add(d != null ? d.floatValue() : 0.0f);
        }
        return result;
    }

    public double cosineSimilarity(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return 0.0;
        double dot = 0.0, n1 = 0.0, n2 = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            dot += v1.get(i) * v2.get(i);
            n1 += v1.get(i) * v1.get(i);
            n2 += v2.get(i) * v2.get(i);
        }
        double denom = Math.sqrt(n1) * Math.sqrt(n2);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
