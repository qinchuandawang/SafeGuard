package com.sdu.safeguard.rag;

import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.RAGConfig;
import com.sdu.safeguard.config.SiliconFlowConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class EmbeddingService {

    private final RestTemplate restTemplate;
    private final RAGConfig ragConfig;
    private final SiliconFlowConfig siliconFlowConfig;
    private final Cache<String, List<Float>> embeddingCache;
    private boolean apiKeyAvailable = false;
    private static final String MISSING_API_KEY_MSG =
            "SiliconFlow API Key 未配置！RAG 向量检索将不可用，仅执行关键词检索。\n" +
            "请配置 application.yml 中的 siliconflow.api-key";

    public EmbeddingService(RestTemplate restTemplate,
                            RAGConfig ragConfig,
                            SiliconFlowConfig siliconFlowConfig,
                            @Qualifier("embeddingCache") Cache<String, List<Float>> embeddingCache) {
        this.restTemplate = restTemplate;
        this.ragConfig = ragConfig;
        this.siliconFlowConfig = siliconFlowConfig;
        this.embeddingCache = embeddingCache;
    }

    @PostConstruct
    public void init() {
        String apiKey = siliconFlowConfig.getApiKey();
        apiKeyAvailable = apiKey != null && !apiKey.isBlank()
                && !"<your-siliconflow-api-key>".equals(apiKey);
        if (!apiKeyAvailable) {
            log.error(MISSING_API_KEY_MSG);
        } else {
            log.info("Embedding服务就绪: model={}, rerank={}",
                    siliconFlowConfig.getEmbeddingModel(), siliconFlowConfig.getRerankModel());
        }
    }

    public boolean isAvailable() {
        return apiKeyAvailable;
    }

    public List<Float> getEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return fallbackEmbedding(text != null ? text : "");
        }
        if (!apiKeyAvailable) {
            return fallbackEmbedding(text);
        }

        List<Float> cached = embeddingCache.getIfPresent(text);
        if (cached != null) {
            return cached;
        }

        List<Float> vector = callEmbeddingAPI(text);
        if (vector != null) {
            embeddingCache.put(text, vector);
        } else {
            vector = fallbackEmbedding(text);
        }
        return vector;
    }

    private List<Float> callEmbeddingAPI(String text) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", siliconFlowConfig.getEmbeddingModel());
            request.put("input", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(siliconFlowConfig.getApiKey());
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
            return null;
        } catch (Exception e) {
            log.error("嵌入服务调用失败: {}", e.getMessage());
            return null;
        }
    }

    public List<List<Float>> batchGetEmbedding(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (!apiKeyAvailable) {
            List<List<Float>> fallbacks = new ArrayList<>(texts.size());
            for (String text : texts) fallbacks.add(fallbackEmbedding(text != null ? text : ""));
            return fallbacks;
        }

        List<String> uncachedTexts = new ArrayList<>();
        List<Integer> uncachedIndices = new ArrayList<>();
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                results.set(i, fallbackEmbedding(""));
                continue;
            }
            List<Float> cached = embeddingCache.getIfPresent(text);
            if (cached != null) {
                results.set(i, cached);
            } else {
                uncachedTexts.add(text);
                uncachedIndices.add(i);
            }
        }

        if (!uncachedTexts.isEmpty()) {
            List<List<Float>> batchVectors = callBatchEmbeddingAPI(uncachedTexts);
            for (int j = 0; j < uncachedTexts.size(); j++) {
                String text = uncachedTexts.get(j);
                List<Float> vector = batchVectors.get(j);
                if (vector != null) {
                    embeddingCache.put(text, vector);
                } else {
                    vector = fallbackEmbedding(text);
                }
                results.set(uncachedIndices.get(j), vector);
            }
        }

        return results;
    }

    /**
     * 批量调用嵌入 API：一次请求获取多个文本的向量
     */
    @SuppressWarnings("unchecked")
    private List<List<Float>> callBatchEmbeddingAPI(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", siliconFlowConfig.getEmbeddingModel());
            request.put("input", texts);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(siliconFlowConfig.getApiKey());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            Map<String, Object> response = restTemplate.postForObject(
                    siliconFlowConfig.getEmbeddingUrl(), entity, Map.class);

            if (response != null && response.containsKey("data")) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                List<List<Float>> result = new ArrayList<>(Collections.nCopies(texts.size(), null));
                for (Map<String, Object> item : data) {
                    Object idxObj = item.get("index");
                    if (idxObj instanceof Number idx) {
                        List<Double> embedding = (List<Double>) item.get("embedding");
                        if (embedding != null && idx.intValue() < texts.size()) {
                            result.set(idx.intValue(), convertToFloat(embedding));
                        }
                    }
                }
                return result;
            }
            log.warn("批量嵌入服务返回异常: {}", response);
            List<List<Float>> fallback = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) fallback.add(null);
            return fallback;
        } catch (Exception e) {
            log.error("批量嵌入服务调用失败: {}", e.getMessage());
            List<List<Float>> fallback = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) fallback.add(null);
            return fallback;
        }
    }

    /**
     * 回退嵌入：基于字符 n-gram 频率的确定性嵌入。
     * 语义近似性来自于：相似文本使用相似的字符分布 → 余弦相似度有意义。
     * 虽然不是 BGE 但有实际检索能力，远优于零向量或随机向量。
     */
    private List<Float> fallbackEmbedding() {
        return fallbackEmbedding("");
    }

    private List<Float> fallbackEmbedding(String text) {
        int dim = ragConfig.getEmbeddingDimension();
        if (text == null || text.isBlank()) {
            List<Float> zero = new ArrayList<>(dim);
            for (int i = 0; i < dim; i++) zero.add(0.0f);
            return zero;
        }

        double[] features = new double[dim];
        int len = text.length();

        // 1-gram (单字) 频率
        for (int i = 0; i < len; i++) {
            int idx = Math.abs(text.charAt(i) * 31) % dim;
            features[idx] += 1.0;
        }
        // 2-gram (双字) 频率 — 捕获词语级信息
        for (int i = 0; i < len - 1; i++) {
            int idx = Math.abs((text.charAt(i) * 31 + text.charAt(i + 1)) * 17) % dim;
            features[idx] += 0.5;
        }
        // 3-gram (三字) 频率 — 捕获短短语级信息
        for (int i = 0; i < len - 2; i++) {
            int idx = Math.abs((text.charAt(i) * 31 + text.charAt(i + 1) * 17 + text.charAt(i + 2)) * 7) % dim;
            features[idx] += 0.25;
        }

        double norm = 0.0;
        for (int i = 0; i < dim; i++) {
            norm += features[i] * features[i];
        }
        norm = Math.sqrt(norm);

        List<Float> vec = new ArrayList<>(dim);
        for (int i = 0; i < dim; i++) {
            vec.add(norm > 0 ? (float) (features[i] / norm) : 0.0f);
        }
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

    /**
     * Cross-encoder rerank: 调用 SiliconFlow rerank API 对结果重新排序。
     */
    @SuppressWarnings("unchecked")
    public List<Double> rerank(String query, List<String> documents) {
        if (query == null || documents == null || documents.isEmpty()) return List.of();
        if (!apiKeyAvailable) {
            return documents.stream().map(d -> -1.0).toList();
        }

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", siliconFlowConfig.getRerankModel());
            request.put("query", query);
            request.put("documents", documents);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(siliconFlowConfig.getApiKey());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    siliconFlowConfig.getRerankUrl(),
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) return documents.stream().map(d -> -1.0).toList();

            Object resultsObj = body.get("results");
            if (!(resultsObj instanceof List<?> results)) {
                return documents.stream().map(d -> -1.0).toList();
            }

            double[] scores = new double[documents.size()];
            Arrays.fill(scores, 0.0);
            for (Object r : results) {
                if (r instanceof Map<?, ?> result) {
                    Object idxObj = result.get("index");
                    Object scoreObj = result.get("relevance_score");
                    if (idxObj instanceof Number idx && scoreObj instanceof Number score) {
                        int i = idx.intValue();
                        if (i >= 0 && i < scores.length) {
                            scores[i] = score.doubleValue();
                        }
                    }
                }
            }

            log.debug("Rerank 完成: {} 篇文档", documents.size());
            List<Double> scoreList = new ArrayList<>(documents.size());
            for (double s : scores) scoreList.add(s);
            return scoreList;
        } catch (RestClientException e) {
            log.warn("Rerank API 调用失败，降级: {}", e.getMessage());
            return documents.stream().map(d -> -1.0).toList();
        }
    }
}
