package com.sdu.safeguard.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean("rateLimitBuckets")
    public Cache<String, Bucket> rateLimitBuckets() {
        return Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .maximumSize(10_000)
                .recordStats()
                .build();
    }

    @Bean("shortTermMemoryCache")
    public Cache<String, Object> shortTermMemoryCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(5_000)
                .recordStats()
                .build();
    }

    @Bean("llmResponseCache")
    public Cache<String, String> llmResponseCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(2_000)
                .recordStats()
                .build();
    }

    @Bean("embeddingCache")
    public Cache<String, List<Float>> embeddingCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(5_000)
                .recordStats()
                .build();
    }

    @Bean("serviceHealthCache")
    public Cache<String, Boolean> serviceHealthCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(50)
                .build();
    }

    @Bean("knowledgeContextCache")
    public Cache<String, String> knowledgeContextCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(1_000)
                .recordStats()
                .build();
    }

    @Bean("ragResultCache")
    public Cache<String, List<com.sdu.safeguard.dto.RagQueryResult>> ragResultCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(200)
                .recordStats()
                .build();
    }
}
