package com.sdu.safeguard.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.MemoryConfig;
import com.sdu.safeguard.dto.MemoryItem;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MemoryService {

    private final MemoryConfig memoryConfig;
    private final Cache<String, Object> shortTermMemory;
    private final ObjectMapper objectMapper;

    private final Map<String, List<MemoryItem>> longTermMemory = new ConcurrentHashMap<>();
    private final Map<String, MemoryItem> globalLongTermIndex = new ConcurrentHashMap<>();

    public MemoryService(MemoryConfig memoryConfig,
                         @Qualifier("shortTermMemoryCache") Cache<String, Object> shortTermMemory,
                         ObjectMapper objectMapper) {
        this.memoryConfig = memoryConfig;
        this.shortTermMemory = shortTermMemory;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadLongTermMemory();
        log.info("记忆系统初始化完成: Caffeine STM自动过期=30min, LTM最大={}",
                memoryConfig.getLongTermMaxSize());
    }

    public void addShortTerm(String sessionId, String role, String content, List<String> tags) {
        @SuppressWarnings("unchecked")
        List<MemoryItem> stm = (List<MemoryItem>) shortTermMemory.get(sessionId, k -> new ArrayList<>());

        MemoryItem item = MemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .timestamp(Instant.now().toEpochMilli())
                .type("SHORT_TERM")
                .importance(computeImportance(content))
                .tags(tags)
                .build();

        stm.add(item);

        if (stm.size() > memoryConfig.getShortTermMaxSize()) {
            stm.remove(0);
        }

        if (item.getImportance() >= memoryConfig.getLongTermImportanceThreshold()) {
            addLongTerm(item);
        }
    }

    @SuppressWarnings("unchecked")
    public List<MemoryItem> getShortTerm(String sessionId) {
        List<MemoryItem> items = (List<MemoryItem>) shortTermMemory.getIfPresent(sessionId);
        return items != null ? items : new ArrayList<>();
    }

    public void clearShortTerm(String sessionId) {
        shortTermMemory.invalidate(sessionId);
    }

    public void addLongTerm(MemoryItem item) {
        String sessionId = item.getSessionId();
        List<MemoryItem> ltm = longTermMemory.computeIfAbsent(sessionId, k -> new ArrayList<>());

        MemoryItem longTermItem = MemoryItem.builder()
                .id(item.getId())
                .sessionId(sessionId)
                .role(item.getRole())
                .content(item.getContent())
                .timestamp(item.getTimestamp())
                .type("LONG_TERM")
                .importance(item.getImportance())
                .tags(item.getTags())
                .summary(generateSummary(item.getContent()))
                .build();

        ltm.add(longTermItem);
        globalLongTermIndex.put(longTermItem.getId(), longTermItem);

        if (ltm.size() > memoryConfig.getLongTermMaxSize()) {
            ltm.sort(Comparator.comparingDouble(MemoryItem::getImportance));
            MemoryItem removed = ltm.remove(0);
            globalLongTermIndex.remove(removed.getId());
        }

        persistLongTermMemory();
    }

    public List<MemoryItem> getLongTerm(String sessionId) {
        return longTermMemory.getOrDefault(sessionId, new ArrayList<>());
    }

    public List<MemoryItem> retrieveRelevantMemory(String sessionId, String query) {
        List<MemoryItem> allMemories = new ArrayList<>(getLongTerm(sessionId));
        allMemories.addAll(getShortTerm(sessionId));

        if (allMemories.isEmpty() || query == null || query.isBlank()) {
            return allMemories;
        }

        String lowerQuery = query.toLowerCase();
        String[] queryKeywords = lowerQuery.split("[，。！？、\\s]+");

        return allMemories.stream()
                .sorted((a, b) -> {
                    double scoreA = computeRelevance(a, lowerQuery, queryKeywords);
                    double scoreB = computeRelevance(b, lowerQuery, queryKeywords);
                    return Double.compare(scoreB, scoreA);
                })
                .limit(memoryConfig.getMemoryRetrievalTopK())
                .collect(Collectors.toList());
    }

    public String formatMemoryContext(String sessionId, String query) {
        List<MemoryItem> memories = retrieveRelevantMemory(sessionId, query);
        if (memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【历史记忆】\n");
        for (MemoryItem mem : memories) {
            String prefix = "LONG_TERM".equals(mem.getType()) ? "[长期]" : "[短期]";
            sb.append(prefix).append(" [").append(mem.getRole()).append("] ");
            if (mem.getSummary() != null) {
                sb.append(mem.getSummary());
            } else {
                sb.append(truncate(mem.getContent(), 100));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private double computeImportance(String content) {
        if (content == null || content.isBlank()) return 0.0;
        double score = 0.3;
        score += Math.min(content.length() / 500.0, 0.2);
        String[] importantIndicators = {"转账", "验证码", "报警", "被骗", "损失",
                "银行卡", "身份证", "密码", "金额", "账号"};
        for (String indicator : importantIndicators) {
            if (content.contains(indicator)) score += 0.05;
        }
        return Math.min(score, 1.0);
    }

    private String generateSummary(String content) {
        if (content == null || content.isBlank()) return "";
        if (content.length() <= 50) return content;
        return content.substring(0, 50) + "...";
    }

    private double computeRelevance(MemoryItem item, String query, String[] keywords) {
        double score = 0.0;
        String content = (item.getContent() + " " + (item.getSummary() != null ? item.getSummary() : "")).toLowerCase();
        for (String kw : keywords) {
            if (kw.length() < 2) continue;
            if (content.contains(kw)) score += 1.0;
        }
        long ageHours = (System.currentTimeMillis() - item.getTimestamp()) / 3600000;
        double timeDecay = Math.max(0.5, 1.0 - ageHours / (memoryConfig.getLongTermTtlHours() * 1.0));
        score *= timeDecay;
        return score;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private void persistLongTermMemory() {
        try {
            Path path = Paths.get(memoryConfig.getStoragePath(), "long_term_memory.json");
            Files.createDirectories(path.getParent());
            List<MemoryItem> allItems = longTermMemory.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(allItems);
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("持久化长期记忆失败", e);
        }
    }

    private void loadLongTermMemory() {
        try {
            Path path = Paths.get(memoryConfig.getStoragePath(), "long_term_memory.json");
            if (!Files.exists(path)) {
                log.debug("未找到持久化记忆文件，使用空记忆库");
                return;
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.isBlank() || !json.contains("[")) return;

            List<Map<String, Object>> items = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> item : items) {
                String sessionId = (String) item.get("sessionId");
                if (sessionId == null) continue;
                MemoryItem memItem = MemoryItem.builder()
                        .id((String) item.get("id"))
                        .sessionId(sessionId)
                        .role((String) item.get("role"))
                        .content((String) item.get("content"))
                        .summary((String) item.get("summary"))
                        .timestamp(item.get("timestamp") instanceof Number
                                ? ((Number) item.get("timestamp")).longValue() : 0)
                        .importance(item.get("importance") instanceof Number
                                ? ((Number) item.get("importance")).doubleValue() : 0.0)
                        .type("LONG_TERM")
                        .build();
                longTermMemory.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(memItem);
                globalLongTermIndex.put(memItem.getId(), memItem);
            }
            log.info("已加载 {} 条持久化记忆", globalLongTermIndex.size());
        } catch (Exception e) {
            log.error("加载持久化记忆失败", e);
        }
    }
}
