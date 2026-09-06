package com.sdu.safeguard.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    private final MeterRegistry meterRegistry;

    public CacheConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean("rateLimitBuckets")
    public Cache<String, Bucket> rateLimitBuckets() {
        return monitor("rate-limit", Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .maximumSize(10_000)
                .recordStats()
                .build());
    }

    @Bean("shortTermMemoryCache")
    public Cache<String, Object> shortTermMemoryCache() {
        return monitor("short-term-memory", Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .maximumSize(5_000)
                .recordStats()
                .build());
    }

    @Bean("llmResponseCache")
    public Cache<String, String> llmResponseCache() {
        return monitor("llm-response", Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(2_000)
                .recordStats()
                .build());
    }

    @Bean("embeddingCache")
    public Cache<String, List<Float>> embeddingCache() {
        return monitor("embedding", Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(5_000)
                .recordStats()
                .build());
    }

    @Bean("serviceHealthCache")
    public Cache<String, Boolean> serviceHealthCache() {
        return monitor("service-health", Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(50)
                .recordStats()
                .build());
    }

    @Bean("knowledgeContextCache")
    public Cache<String, String> knowledgeContextCache() {
        return monitor("knowledge-context", Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(1_000)
                .recordStats()
                .build());
    }

    @Bean("ragResultCache")
    public Cache<String, List<com.sdu.safeguard.dto.RagQueryResult>> ragResultCache() {
        return monitor("rag-result", Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(200)
                .recordStats()
                .build());
    }

@Bean("ragSemanticCache")
    public Cache<String, com.sdu.safeguard.rag.RAGService.SemanticCacheEntry> ragSemanticCache() {
        return monitor("rag-semantic-result", Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(200)
                .recordStats()
                .build());
    }

    @Bean("agenticResultCache")
    public Cache<String, com.sdu.safeguard.rag.agentic.AgenticRagResult> agenticResultCache() {
        return monitor("rag-agentic-result", Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(200)
                .recordStats()
                .build());
    }

    @Bean("llmSemanticCache")
    public Cache<String, com.sdu.safeguard.service.SemanticLlmCache.Entry> llmSemanticCache() {
        return monitor("llm-semantic", Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(200)
                .recordStats()
                .build());
    }

    private <K, V> Cache<K, V> monitor(String cacheName, Cache<K, V> cache) {
        CaffeineCacheMetrics.monitor(meterRegistry, cache, cacheName);
        return cache;
    }
}
