package com.sdu.safeguard.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sdu.safeguard.config.RAGConfig;
import com.sdu.safeguard.rag.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticLlmCacheTest {

    private SemanticLlmCache cache;

    @BeforeEach
    void setUp() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.cosineSimilarity(anyList(), anyList())).thenAnswer(inv -> {
            List<Float> a = inv.getArgument(0);
            List<Float> b = inv.getArgument(1);
            double dot = 0, n1 = 0, n2 = 0;
            for (int i = 0; i < a.size(); i++) {
                dot += a.get(i) * b.get(i);
                n1 += a.get(i) * a.get(i);
                n2 += b.get(i) * b.get(i);
            }
            return n1 == 0 || n2 == 0 ? 0.0 : dot / (Math.sqrt(n1) * Math.sqrt(n2));
        });
        when(embeddingService.getEmbedding("问A")).thenReturn(vec(0.5));
        when(embeddingService.getEmbedding("问B")).thenReturn(vec(0.5));
        when(embeddingService.getEmbedding("问C")).thenReturn(vec(0.0));

        RAGConfig ragConfig = new RAGConfig();
        ragConfig.setLlmSemanticCacheThreshold(0.90);
        Cache<String, SemanticLlmCache.Entry> store = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(100)
                .build();
        cache = new SemanticLlmCache(embeddingService, ragConfig, store);
    }

    private static List<Float> vec(double value) {
        return List.of((float) value, (float) value, (float) value, (float) value);
    }

    @Test
    void semanticSimilarQueryReusesCachedAnswer() {
        cache.put("text-analysis", "问A", "答案A");
        // 问B 与 问A 余弦相似度 = 1.0 >= 0.90，命中
        assertEquals("答案A", cache.get("text-analysis", "问B"));
    }

    @Test
    void differentSceneIsIsolated() {
        cache.put("text-analysis", "问A", "答案A");
        assertNull(cache.get("simulation:X", "问B"));
    }

    @Test
    void belowThresholdDoesNotReuse() {
        cache.put("text-analysis", "问A", "答案A");
        // 问C 与 问A 余弦相似度 = 0.0 < 0.90，不命中
        assertNull(cache.get("text-analysis", "问C"));
    }

    @Test
    void blankQueryReturnsNull() {
        cache.put("text-analysis", "问A", "答案A");
        assertNull(cache.get("text-analysis", "   "));
    }

    @Test
    void emptyCacheReturnsNull() {
        assertNull(cache.get("text-analysis", "问B"));
    }
}
