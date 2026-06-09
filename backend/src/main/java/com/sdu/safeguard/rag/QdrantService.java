package com.sdu.safeguard.rag;

import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.RAGConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@DependsOn("qdrantContainerManager")
public class QdrantService {

    private final RAGConfig ragConfig;
    private final RestTemplate restTemplate;
    private final Cache<String, List<Float>> embeddingCache;
    private String baseUrl;
    private boolean useRealQdrant = false;

    /** Qdrant 不可用时的内存回退存储（ConcurrentHashMap 保证线程安全） */
    private final Map<String, Map<String, StoredVector>> fallbackStore = new ConcurrentHashMap<>();

    private static final String CONTENT_FIELD = "content";
    private static final String CATEGORY_FIELD = "category";
    private static final String SOURCE_FIELD = "source";
    private static final String TAGS_FIELD = "tags";

    public QdrantService(RAGConfig ragConfig,
                         RestTemplate restTemplate,
                         @Qualifier("embeddingCache") Cache<String, List<Float>> embeddingCache) {
        this.ragConfig = ragConfig;
        this.restTemplate = restTemplate;
        this.embeddingCache = embeddingCache;
    }

    @PostConstruct
    public void init() {
        baseUrl = String.format("http://%s:%d", ragConfig.getQdrantHost(), ragConfig.getQdrantPort());
        try {
            ResponseEntity<String> healthResp = restTemplate.getForEntity(baseUrl + "/healthz", String.class);
            if (healthResp.getStatusCode().is2xxSuccessful()) {
                useRealQdrant = true;
                log.info("Qdrant 连接成功: {}:{}", ragConfig.getQdrantHost(), ragConfig.getQdrantPort());
                ensureCollection(ragConfig.getCollectionName(), ragConfig.getEmbeddingDimension());
            } else {
                handleQdrantUnavailable("健康检查返回非 200");
            }
        } catch (Exception e) {
            handleQdrantUnavailable(e.getMessage());
        }
    }

    /** 为指定集合创建/确保存在 */
    public void ensureCollection(String collectionName, int dimension) {
        if (!useRealQdrant) return;
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl + "/collections/" + collectionName,
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null
                    && resp.getBody().get("result") != null) {
                log.info("集合已存在: {}", collectionName);
                return;
            }
        } catch (Exception e) {
            log.debug("集合不存在, 准备创建: {}", collectionName);
        }

        Map<String, Object> vectorsConfig = new LinkedHashMap<>();
        vectorsConfig.put("size", dimension);
        vectorsConfig.put("distance", "Cosine");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("vectors", vectorsConfig);

        try {
            restTemplate.put(baseUrl + "/collections/" + collectionName, request);
            log.info("集合创建完成: {}, dim={}, distance=Cosine", collectionName, dimension);
        } catch (Exception e) {
            log.error("创建 Qdrant 集合失败: {}", e.getMessage());
            useRealQdrant = false;
        }
    }

    private void handleQdrantUnavailable(String reason) {
        if (ragConfig.isAllowInMemoryFallback()) {
            log.warn("Qdrant 不可用 ({}), 使用内存回退模式", reason);
            useRealQdrant = false;
        } else {
            throw new IllegalStateException("Qdrant 连接失败: " + reason);
        }
    }

    @PreDestroy
    public void cleanup() {}

    // ============ 默认集合操作（RAG 知识库） ============

    private String defaultCollection() {
        return ragConfig.getCollectionName();
    }

    public void insert(String chunkId, List<Float> vector, Map<String, Object> metadata) {
        insertTo(chunkId, vector, metadata, defaultCollection());
    }

    public void batchInsert(List<String> chunkIds, List<List<Float>> vectors,
                            List<Map<String, Object>> metadatas) {
        batchInsertTo(chunkIds, vectors, metadatas, defaultCollection());
    }

    public List<ScoredResult> search(List<Float> queryVector, int topK) {
        return searchFrom(queryVector, topK, null, defaultCollection());
    }

    public List<ScoredResult> search(List<Float> queryVector, int topK, Map<String, String> payloadFilter) {
        return searchFrom(queryVector, topK, payloadFilter, defaultCollection());
    }

    public int getCollectionSize() {
        return getCollectionSize(defaultCollection());
    }

    public List<Map<String, Object>> getAllMetadata() {
        return getAllMetadata(defaultCollection());
    }

    public void dropCollection() {
        dropCollection(defaultCollection());
        ensureCollection(defaultCollection(), ragConfig.getEmbeddingDimension());
    }

    // ============ 指定集合操作（支持 LTM user_memories） ============

    public void insertTo(String chunkId, List<Float> vector, Map<String, Object> metadata, String collectionName) {
        if (useRealQdrant) {
            insertToQdrant(chunkId, vector, metadata, collectionName);
        } else {
            insertToFallback(chunkId, vector, metadata, collectionName);
        }
    }

    public void batchInsertTo(List<String> chunkIds, List<List<Float>> vectors,
                               List<Map<String, Object>> metadatas, String collectionName) {
        if (chunkIds == null || vectors == null) return;
        if (!useRealQdrant) {
            for (int i = 0; i < chunkIds.size(); i++) {
                List<Float> v = i < vectors.size() ? vectors.get(i) : null;
                Map<String, Object> m = i < metadatas.size() ? metadatas.get(i) : new HashMap<>();
                if (v != null) insertToFallback(chunkIds.get(i), v, m, collectionName);
            }
            return;
        }

        int batchSize = 100;
        for (int batchStart = 0; batchStart < chunkIds.size(); batchStart += batchSize) {
            int end = Math.min(batchStart + batchSize, chunkIds.size());
            List<Map<String, Object>> points = new ArrayList<>();
            for (int i = batchStart; i < end; i++) {
                Map<String, Object> m = metadatas.get(i);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("chunkId", chunkIds.get(i));
                payload.put(CONTENT_FIELD, str(m.get("content")));
                payload.put(CATEGORY_FIELD, str(m.get("category")));
                payload.put(SOURCE_FIELD, str(m.get("source")));
                payload.put(TAGS_FIELD, str(m.get("tags")));

                Map<String, Object> point = new LinkedHashMap<>();
                // Qdrant 1.7+ 严格要求 point ID 为 unsigned integer 或 UUID
                // 业务 chunkId 形如 "fixed_anti_fraud_knowledge.txt_0" 含 '.' 和 '_'，非合法 ID
                // 用 UUID v5 派生（同样输入永远同样输出，原 chunkId 保留在 payload 中）
                point.put("id", toQdrantId(chunkIds.get(i)));
                point.put("vector", vectors.get(i));
                point.put("payload", payload);
                points.add(point);
            }

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("points", points);

            try {
                restTemplate.put(baseUrl + "/collections/" + collectionName + "/points", request);
                log.debug("Qdrant 批量插入: {} 条到 {}", points.size(), collectionName);
            } catch (Exception e) {
                log.warn("Qdrant 批量插入失败: {}", e.getMessage());
            }
        }
    }

    public List<ScoredResult> searchFrom(List<Float> queryVector, int topK, String collectionName) {
        return searchFrom(queryVector, topK, null, collectionName);
    }

    @SuppressWarnings("unchecked")
    public List<ScoredResult> searchFrom(List<Float> queryVector, int topK,
                                          Map<String, String> payloadFilter, String collectionName) {
        if (!useRealQdrant) {
            return searchFallback(queryVector, topK, payloadFilter, collectionName);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("vector", queryVector);
        request.put("limit", topK);
        request.put("with_payload", true);
        request.put("with_vector", false);

        if (payloadFilter != null && !payloadFilter.isEmpty()) {
            List<Map<String, Object>> mustConditions = new ArrayList<>();
            for (Map.Entry<String, String> entry : payloadFilter.entrySet()) {
                Map<String, Object> condition = new LinkedHashMap<>();
                condition.put("key", entry.getKey());
                condition.put("match", Map.of("value", entry.getValue()));
                mustConditions.add(condition);
            }
            request.put("filter", Map.of("must", mustConditions));
        }

        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl + "/collections/" + collectionName + "/points/search",
                    HttpMethod.POST, new HttpEntity<>(request),
                    new ParameterizedTypeReference<>() {});
            if (resp.getBody() == null) return List.of();

            Object resultObj = resp.getBody().get("result");
            if (!(resultObj instanceof List)) return List.of();

            List<Map<String, Object>> results = (List<Map<String, Object>>) resultObj;
            return results.stream().map(r -> {
                String id = String.valueOf(r.get("id"));
                double score = ((Number) r.getOrDefault("score", 0.0)).doubleValue();
                Map<String, Object> payload = (Map<String, Object>) r.getOrDefault("payload", new HashMap<>());
                return new ScoredResult(id, score, payload);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Qdrant 搜索失败 ({}): {}", collectionName, e.getMessage());
            return List.of();
        }
    }

    public int getCollectionSize(String collectionName) {
        if (useRealQdrant) {
            try {
                ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                        baseUrl + "/collections/" + collectionName + "/points/count",
                        HttpMethod.POST, new HttpEntity<>(Collections.emptyMap()),
                        new ParameterizedTypeReference<>() {});
                if (resp.getBody() != null) {
                    Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
                    if (result != null) {
                        return ((Number) result.getOrDefault("count", 0)).intValue();
                    }
                }
            } catch (Exception e) {
                log.debug("获取集合大小失败: {}", e.getMessage());
            }
            return 0;
        }
        Map<String, StoredVector> cs = fallbackStore.get(collectionName);
        return cs != null ? cs.size() : 0;
    }

    public List<Map<String, Object>> getAllMetadata(String collectionName) {
        if (useRealQdrant) {
            return getAllMetadataFromQdrant(collectionName);
        }
        Map<String, StoredVector> cs = fallbackStore.get(collectionName);
        if (cs == null) return List.of();
        return cs.values().stream().map(sv -> sv.metadata).collect(Collectors.toList());
    }

    public void dropCollection(String collectionName) {
        if (useRealQdrant) {
            try {
                restTemplate.delete(baseUrl + "/collections/" + collectionName);
                log.info("删除集合: {}", collectionName);
            } catch (Exception e) {
                log.warn("删除集合失败: {}", e.getMessage());
            }
        } else {
            fallbackStore.remove(collectionName);
            fallbackStore.put(collectionName, new ConcurrentHashMap<>());
        }
    }

    public boolean isUsingRealQdrant() { return useRealQdrant; }

    // ============ 内部方法 ============

    /**
     * 将业务 chunkId 转换为 Qdrant 兼容的 UUID。
     * Qdrant 1.7+ 只接受 unsigned integer 或 UUID，业务 chunkId 形如
     * "fixed_anti_fraud_knowledge.txt_0"，含 '.' 和 '_'，非合法 ID。
     * 使用 UUID v3（基于名称的 MD5）派生：同样输入永远得到同样输出，
     * 便于跨重启去重；原 chunkId 仍保留在 payload 中，业务可读。
     */
    static String toQdrantId(String chunkId) {
        if (chunkId == null) return null;
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }


    private void insertToFallback(String chunkId, List<Float> vector,
                                   Map<String, Object> metadata, String collectionName) {
        fallbackStore.computeIfAbsent(collectionName, k -> new ConcurrentHashMap<>())
                .put(chunkId, new StoredVector(vector, metadata));
    }

    @SuppressWarnings("unchecked")
    private void insertToQdrant(String chunkId, List<Float> vector,
                                 Map<String, Object> metadata, String collectionName) {
        Map<String, Object> point = new LinkedHashMap<>();
        // Qdrant 1.7+ 严格要求 point ID 为 unsigned integer 或 UUID
        point.put("id", toQdrantId(chunkId));
        point.put("vector", vector);
        if (metadata != null && !metadata.isEmpty()) {
            point.put("payload", metadata);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("points", List.of(point));

        try {
            restTemplate.put(baseUrl + "/collections/" + collectionName + "/points", request);
        } catch (Exception e) {
            log.warn("Qdrant 插入失败: {}", e.getMessage());
        }
    }

    private void ensureCollection() {
        ensureCollection(defaultCollection(), ragConfig.getEmbeddingDimension());
    }

    private List<ScoredResult> searchFallback(List<Float> queryVector, int topK,
                                               Map<String, String> payloadFilter, String collectionName) {
        Map<String, StoredVector> collectionStore = fallbackStore.get(collectionName);
        if (collectionStore == null || collectionStore.isEmpty()) return List.of();

        int searchK = Math.min(topK * 3, collectionStore.size());
        return collectionStore.entrySet().parallelStream()
                .filter(entry -> {
                    if (payloadFilter == null || payloadFilter.isEmpty()) return true;
                    for (Map.Entry<String, String> f : payloadFilter.entrySet()) {
                        Object val = entry.getValue().metadata.get(f.getKey());
                        if (val == null || !val.toString().equals(f.getValue())) return false;
                    }
                    return true;
                })
                .map(entry -> {
                    double similarity = cosineSimilarity(queryVector, entry.getValue().vector);
                    return new ScoredResult(entry.getKey(), similarity, entry.getValue().metadata);
                })
                .filter(r -> r.score > 0)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(searchK)
                .collect(Collectors.toList());
    }

    private double cosineSimilarity(List<Float> v1, List<Float> v2) {
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAllMetadataFromQdrant(String collectionName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("limit", 10000);
        request.put("with_payload", true);
        request.put("with_vector", false);

        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl + "/collections/" + collectionName + "/points/scroll",
                    HttpMethod.POST, new HttpEntity<>(request),
                    new ParameterizedTypeReference<>() {});
            if (resp.getBody() == null) return List.of();

            Object resultObj = resp.getBody().get("result");
            if (!(resultObj instanceof Map)) return List.of();

            Map<String, Object> result = (Map<String, Object>) resultObj;
            Object pointsObj = result.get("points");
            if (!(pointsObj instanceof List)) return List.of();

            List<Map<String, Object>> points = (List<Map<String, Object>>) pointsObj;
            return points.stream().map(p -> {
                Map<String, Object> payload = (Map<String, Object>) p.getOrDefault("payload", new HashMap<>());
                return new HashMap<>(payload);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Qdrant 获取全部数据失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    public static class ScoredResult {
        public final String chunkId;
        public final double score;
        public final Map<String, Object> metadata;

        ScoredResult(String chunkId, double score, Map<String, Object> metadata) {
            this.chunkId = chunkId;
            this.score = score;
            this.metadata = metadata;
        }
    }

    private static class StoredVector {
        final List<Float> vector;
        final Map<String, Object> metadata;

        StoredVector(List<Float> vector, Map<String, Object> metadata) {
            this.vector = vector;
            this.metadata = metadata;
        }
    }
}