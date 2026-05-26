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
                ensureCollection();
            } else {
                log.warn("Qdrant 不可用, 使用回退模式");
                useRealQdrant = false;
            }
        } catch (Exception e) {
            log.warn("Qdrant 连接失败 ({}:{}), 使用回退模式: {}",
                    ragConfig.getQdrantHost(), ragConfig.getQdrantPort(), e.getMessage());
            useRealQdrant = false;
        }
    }

    @PreDestroy
    public void cleanup() {
        // Qdrant 客户端无需显式关闭（REST API 无连接池）
    }

    @SuppressWarnings("unchecked")
    private void ensureCollection() {
        String collectionName = ragConfig.getCollectionName();
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
        vectorsConfig.put("size", ragConfig.getEmbeddingDimension());
        vectorsConfig.put("distance", "Cosine");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("vectors", vectorsConfig);

        try {
            restTemplate.put(baseUrl + "/collections/" + collectionName, request);
            log.info("集合创建完成: {}, dim={}, distance=Cosine",
                    collectionName, ragConfig.getEmbeddingDimension());
        } catch (Exception e) {
            log.error("创建 Qdrant 集合失败: {}", e.getMessage());
            useRealQdrant = false;
        }
    }

    public void insert(String chunkId, List<Float> vector, Map<String, Object> metadata) {
        if (useRealQdrant) {
            insertToQdrant(chunkId, vector, metadata);
        } else {
            insertToFallback(chunkId, vector, metadata);
        }
    }

    private void insertToFallback(String chunkId, List<Float> vector, Map<String, Object> metadata) {
        fallbackStore.computeIfAbsent(ragConfig.getCollectionName(), k -> new ConcurrentHashMap<>())
                .put(chunkId, new StoredVector(vector, metadata));
    }

    @SuppressWarnings("unchecked")
    private void insertToQdrant(String chunkId, List<Float> vector, Map<String, Object> metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk_id", chunkId);
        payload.put(CONTENT_FIELD, str(metadata.get("content")));
        payload.put(CATEGORY_FIELD, str(metadata.get("category")));
        payload.put(SOURCE_FIELD, str(metadata.get("source")));
        payload.put(TAGS_FIELD, str(metadata.get("tags")));

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", chunkId);
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("points", List.of(point));

        restTemplate.put(baseUrl + "/collections/" + ragConfig.getCollectionName() + "/points", request);
    }

    @SuppressWarnings("unchecked")
    public void batchInsert(List<String> chunkIds, List<List<Float>> vectors,
                            List<Map<String, Object>> metadatas) {
        if (chunkIds == null || vectors == null) return;

        if (!useRealQdrant) {
            for (int i = 0; i < chunkIds.size(); i++) {
                List<Float> v = i < vectors.size() ? vectors.get(i) : null;
                Map<String, Object> m = i < metadatas.size() ? metadatas.get(i) : new HashMap<>();
                if (v != null) insertToFallback(chunkIds.get(i), v, m);
            }
            return;
        }

        // 每次批量最多 100 条
        int batchSize = 100;
        for (int batchStart = 0; batchStart < chunkIds.size(); batchStart += batchSize) {
            int end = Math.min(batchStart + batchSize, chunkIds.size());
            List<Map<String, Object>> points = new ArrayList<>();
            for (int i = batchStart; i < end; i++) {
                Map<String, Object> m = metadatas.get(i);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("chunk_id", chunkIds.get(i));
                payload.put(CONTENT_FIELD, str(m.get("content")));
                payload.put(CATEGORY_FIELD, str(m.get("category")));
                payload.put(SOURCE_FIELD, str(m.get("source")));
                payload.put(TAGS_FIELD, str(m.get("tags")));

                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", chunkIds.get(i));
                point.put("vector", vectors.get(i));
                point.put("payload", payload);
                points.add(point);
            }

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("points", points);

            try {
                restTemplate.put(baseUrl + "/collections/" + ragConfig.getCollectionName() + "/points", request);
                log.debug("Qdrant 批量插入: {} 条", points.size());
            } catch (Exception e) {
                log.warn("Qdrant 批量插入失败: {}", e.getMessage());
            }
        }
        log.info("Qdrant 批量插入完成: {} 条", chunkIds.size());
    }

    @SuppressWarnings("unchecked")
    public List<ScoredResult> search(List<Float> queryVector, int topK) {
        if (!useRealQdrant) {
            return searchFallback(queryVector, topK);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("vector", queryVector);
        request.put("limit", topK);
        request.put("with_payload", true);
        request.put("with_vector", false);

        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl + "/collections/" + ragConfig.getCollectionName() + "/points/search",
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

                Map<String, Object> meta = new HashMap<>();
                meta.put("chunk_id", payload.getOrDefault("chunk_id", id));
                meta.put(CONTENT_FIELD, str(payload.get(CONTENT_FIELD)));
                meta.put(CATEGORY_FIELD, str(payload.get(CATEGORY_FIELD)));
                meta.put(SOURCE_FIELD, str(payload.get(SOURCE_FIELD)));
                meta.put(TAGS_FIELD, str(payload.get(TAGS_FIELD)));
                return new ScoredResult(id, score, meta);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Qdrant 搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ScoredResult> searchFallback(List<Float> queryVector, int topK) {
        Map<String, StoredVector> collectionStore = fallbackStore.get(ragConfig.getCollectionName());
        if (collectionStore == null || collectionStore.isEmpty()) return List.of();

        int searchK = Math.min(topK * 3, collectionStore.size());
        return collectionStore.entrySet().parallelStream()
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

    public boolean isCollectionReady() {
        return getCollectionSize() > 0;
    }

    @SuppressWarnings("unchecked")
    public int getCollectionSize() {
        if (useRealQdrant) {
            try {
                ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                        baseUrl + "/collections/" + ragConfig.getCollectionName() + "/points/count",
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
        Map<String, StoredVector> cs = fallbackStore.get(ragConfig.getCollectionName());
        return cs != null ? cs.size() : 0;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllMetadata() {
        if (useRealQdrant) {
            return getAllMetadataFromQdrant();
        }
        Map<String, StoredVector> cs = fallbackStore.get(ragConfig.getCollectionName());
        if (cs == null) return List.of();
        return cs.values().stream().map(sv -> sv.metadata).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAllMetadataFromQdrant() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("limit", 10000);
        request.put("with_payload", true);
        request.put("with_vector", false);

        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl + "/collections/" + ragConfig.getCollectionName() + "/points/scroll",
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
                Map<String, Object> meta = new HashMap<>();
                meta.put(CONTENT_FIELD, str(payload.get(CONTENT_FIELD)));
                meta.put(CATEGORY_FIELD, str(payload.get(CATEGORY_FIELD)));
                meta.put(SOURCE_FIELD, str(payload.get(SOURCE_FIELD)));
                meta.put(TAGS_FIELD, str(payload.get(TAGS_FIELD)));
                return meta;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Qdrant 获取全部数据失败: {}", e.getMessage());
            return List.of();
        }
    }

    public void dropCollection() {
        if (useRealQdrant) {
            try {
                restTemplate.delete(baseUrl + "/collections/" + ragConfig.getCollectionName());
            } catch (Exception e) {
                log.warn("删除 Qdrant 集合失败: {}", e.getMessage());
            }
            ensureCollection();
        } else {
            fallbackStore.remove(ragConfig.getCollectionName());
            fallbackStore.put(ragConfig.getCollectionName(), new ConcurrentHashMap<>());
        }
    }

    public boolean isUsingRealQdrant() {
        return useRealQdrant;
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

    /** Qdrant 不可用时内存回退的向量存储结构 */
    private static class StoredVector {
        final List<Float> vector;
        final Map<String, Object> metadata;

        StoredVector(List<Float> vector, Map<String, Object> metadata) {
            this.vector = vector;
            this.metadata = metadata;
        }
    }
}
