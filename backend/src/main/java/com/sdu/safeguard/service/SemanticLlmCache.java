package com.sdu.safeguard.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.RAGConfig;
import com.sdu.safeguard.rag.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * LLM 回复语义缓存 — 精确哈希缓存之外的第二层。
 *
 * 对语义相近（不同措辞但同一意图）的用户查询复用缓存答案，避免重复 LLM 调用：
 *  - 键：场景隔离 + 查询文本的 BGE 向量；
 *  - 命中：与历史条目同一场景且余弦相似度 ≥ {@link RAGConfig#getLlmSemanticCacheThreshold()}；
 *  - 阈值默认 0.90，保持偏高，防止"语义相近但答案不同"的错误复用；
 *  - 未命中时照常走 LLM，成功后写入（不缓存 fallback 与失败结果）。
 */
@Slf4j
@Component
public class SemanticLlmCache {

    /** 缓存条目：场景 + 归一化查询 + 查询向量 + 缓存回复 */
    public record Entry(String scene, String normalizedQuery, List<Float> vector, String response) {
    }

    private final EmbeddingService embeddingService;
    private final RAGConfig ragConfig;
    private final Cache<String, Entry> cache;

    public SemanticLlmCache(EmbeddingService embeddingService,
                            RAGConfig ragConfig,
                            @Qualifier("llmSemanticCache") Cache<String, Entry> cache) {
        this.embeddingService = embeddingService;
        this.ragConfig = ragConfig;
        this.cache = cache;
    }

    /** 语义命中返回缓存回复，否则返回 null。 */
    public String get(String scene, String query) {
        if (query == null || query.isBlank() || cache.asMap().isEmpty()) {
            return null;
        }
        List<Float> queryVector = embeddingVector(query);
        if (queryVector == null) {
            return null;
        }
        double threshold = ragConfig.getLlmSemanticCacheThreshold();
        double best = 0.0;
        String bestResponse = null;
        for (Entry entry : cache.asMap().values()) {
            if (!entry.scene().equals(scene)) {
                continue;
            }
            double similarity = embeddingService.cosineSimilarity(queryVector, entry.vector());
            if (similarity > best) {
                best = similarity;
                bestResponse = entry.response();
            }
        }
        if (best >= threshold && bestResponse != null) {
            log.debug("LLM 语义缓存命中: scene={}, query=\"{}\", similarity={:.2f}", scene, query, best);
            return bestResponse;
        }
        return null;
    }

    /** 写入缓存条目。 */
    public void put(String scene, String query, String response) {
        if (query == null || query.isBlank() || response == null || response.isBlank()) {
            return;
        }
        List<Float> vector = embeddingVector(query);
        if (vector == null) {
            return;
        }
        cache.put(UUID.randomUUID().toString(),
                new Entry(scene, normalize(query), vector, response));
    }

    /** 清空（知识库/配置变更时调用）。 */
    public void clear() {
        cache.invalidateAll();
    }

    public long size() {
        return cache.estimatedSize();
    }

    private List<Float> embeddingVector(String query) {
        try {
            return embeddingService.getEmbedding(normalize(query));
        } catch (Exception e) {
            log.debug("LLM 语义缓存 embedding 失败: {}", e.getMessage());
            return null;
        }
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ");
    }
}
