package com.sdu.safeguard.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.MemoryConfig;
import com.sdu.safeguard.dto.MemoryItem;
import com.sdu.safeguard.rag.EmbeddingService;
import com.sdu.safeguard.rag.QdrantService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;

    private final Map<String, List<MemoryItem>> longTermMemory = new ConcurrentHashMap<>();
    private final Map<String, MemoryItem> globalLongTermIndex = new ConcurrentHashMap<>();

    /** 最近 N 条 STM 的数量 */
    private static final int STM_RECENT_COUNT = 6;
    /** Qdrant 记忆检索 Top-K */
    private static final int LTM_SEARCH_COUNT = 6;
    /** 最终返回的记忆上限 */
    private static final int FINAL_MEMORY_LIMIT = 10;

    public MemoryService(MemoryConfig memoryConfig,
                         @Qualifier("shortTermMemoryCache") Cache<String, Object> shortTermMemory,
                         ObjectMapper objectMapper,
                         EmbeddingService embeddingService,
                         QdrantService qdrantService) {
        this.memoryConfig = memoryConfig;
        this.shortTermMemory = shortTermMemory;
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
    }

    @PostConstruct
    public void init() {
        String collection = memoryConfig.getMemoryCollection();
        if (qdrantService.isUsingRealQdrant()) {
            qdrantService.ensureCollection(collection, memoryConfig.getMemoryEmbeddingDim());
        }
        loadLongTermMemory();
        log.info("记忆系统初始化完成: STM={}, LTM={}, 记忆集合={}",
                memoryConfig.getShortTermMaxSize(), memoryConfig.getLongTermMaxSize(), collection);
    }

    // ==================== 存储 ====================

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

        // STM 超出上限时折叠最早对话而非直接丢弃
        if (stm.size() > memoryConfig.getShortTermMaxSize()) {
            MemoryItem oldest = stm.remove(0);
            // 折叠为摘要标记
            String folded = "[历史对话片段] " + truncate(oldest.getContent(), 30);
            stm.add(0, MemoryItem.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionId(sessionId)
                    .role(oldest.getRole())
                    .content(folded)
                    .timestamp(oldest.getTimestamp())
                    .type("SHORT_TERM")
                    .importance(0.3)
                    .tags(List.of("folded"))
                    .build());
        }

        // 重要内容晋升 LTM
        if (item.getImportance() >= memoryConfig.getLongTermImportanceThreshold()) {
            addLongTerm(item);
        }
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

        // 淘汰低分记忆
        if (ltm.size() > memoryConfig.getLongTermMaxSize()) {
            ltm.sort(Comparator.comparingDouble(MemoryItem::getImportance));
            MemoryItem removed = ltm.remove(0);
            globalLongTermIndex.remove(removed.getId());
        }

        // 写入外部存储: Qdrant (主) + JSON (备份)
        persistToQdrant(longTermItem);
        persistLongTermMemoryBatch();
    }

    private void persistToQdrant(MemoryItem item) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", item.getSessionId());
            metadata.put("content", item.getContent());
            metadata.put("role", item.getRole());
            metadata.put("timestamp", item.getTimestamp());
            metadata.put("importance", item.getImportance());
            metadata.put("summary", item.getSummary() != null ? item.getSummary() : "");
            metadata.put("tags", item.getTags() != null ? String.join(",", item.getTags()) : "");

            List<Float> vector = embeddingService.getEmbedding(item.getContent());
            qdrantService.insertTo(item.getId(), vector, metadata, memoryConfig.getMemoryCollection());
        } catch (Exception e) {
            log.debug("LTM写入Qdrant失败(可忽略): {}", e.getMessage());
        }
    }

    /** 批量持久化（每 5 条或满上限时写文件） */
    private boolean persistLongTermMemoryBatch() {
        if (globalLongTermIndex.size() % 5 == 0
                || longTermMemory.values().stream().mapToInt(List::size).sum() >= memoryConfig.getLongTermMaxSize()) {
            persistLongTermMemory();
            return true;
        }
        return false;
    }

    // ==================== 检索 ====================

    /**
     * 混合检索:
     *   - recent: STM 最后 N 条 (保证时序连续性)
     *   - semantic: Qdrant 记忆集合中按 sessionId 过滤的 Top-N (长期语义匹配)
     *   - merge → dedup → sort → limit
     */
    public List<MemoryItem> retrieveRelevantMemory(String sessionId, String query) {
        List<MemoryItem> stm = getShortTerm(sessionId);
        List<MemoryItem> ltm = getLongTerm(sessionId);

        if ((stm.isEmpty() && ltm.isEmpty()) || query == null || query.isBlank()) {
            List<MemoryItem> all = new ArrayList<>(stm);
            all.addAll(ltm);
            return all;
        }

        Set<String> dedupKeys = new HashSet<>();
        List<ScoredMemory> scored = new ArrayList<>();

        // 1. 最近 N 条 STM (时序连续性)
        int recentCount = Math.min(STM_RECENT_COUNT, stm.size());
        for (int i = stm.size() - recentCount; i < stm.size(); i++) {
            MemoryItem item = stm.get(i);
            String key = item.getContent();
            if (dedupKeys.add(key)) {
                // 最近的第 1 条权重最高 (0.7), 递减
                double recencyWeight = 0.7 - (stm.size() - 1 - i) * 0.08;
                scored.add(new ScoredMemory(item, Math.max(recencyWeight, 0.3)));
            }
        }

        // 2. Qdrant 语义检索 (session 隔离)
        try {
            List<Float> queryVec = embeddingService.getEmbedding(query);
            Map<String, String> filter = Map.of("sessionId", sessionId);
            List<QdrantService.ScoredResult> qdrantResults = qdrantService.searchFrom(
                    queryVec, LTM_SEARCH_COUNT, filter, memoryConfig.getMemoryCollection());

            for (QdrantService.ScoredResult qr : qdrantResults) {
                String content = (String) qr.metadata.getOrDefault("content", "");
                if (content.isBlank() || !dedupKeys.add(content)) continue;

                MemoryItem item = MemoryItem.builder()
                        .id(qr.chunkId)
                        .sessionId(sessionId)
                        .role((String) qr.metadata.getOrDefault("role", "assistant"))
                        .content(content)
                        .timestamp(qr.metadata.get("timestamp") instanceof Number
                                ? ((Number) qr.metadata.get("timestamp")).longValue() : 0)
                        .type("LONG_TERM")
                        .importance(0.5)
                        .summary((String) qr.metadata.getOrDefault("summary", ""))
                        .build();
                scored.add(new ScoredMemory(item, qr.score));
            }
        } catch (Exception e) {
            log.debug("Qdrant 记忆检索不可用，仅使用本地记忆: {}", e.getMessage());
            // 降级：从本地 LTM 做 embedding 检索
            for (MemoryItem item : ltm) {
                String key = item.getContent();
                if (!dedupKeys.add(key)) continue;
                double score = embeddingService.isAvailable()
                        ? embeddingService.cosineSimilarity(
                                embeddingService.getEmbedding(query),
                                embeddingService.getEmbedding(item.getContent()))
                        : 0.3;
                scored.add(new ScoredMemory(item, score));
            }
        }

        // 3. 排序取 Top-K
        List<MemoryItem> result = scored.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(FINAL_MEMORY_LIMIT)
                .map(sm -> sm.item)
                .collect(Collectors.toList());

        log.debug("记忆检索完成: query=\"{}\", recent={}, qdrant={}, total={}→{}",
                truncate(query, 20), recentCount,
                Math.min(LTM_SEARCH_COUNT, scored.size() - recentCount),
                scored.size(), result.size());

        return result;
    }

    // ==================== 记忆格式化 ====================

    public String formatMemoryContext(String sessionId, String query) {
        List<MemoryItem> memories = retrieveRelevantMemory(sessionId, query);
        if (memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【历史记忆】\n");
        for (MemoryItem mem : memories) {
            String prefix = "LONG_TERM".equals(mem.getType()) ? "[长期]" : "[短期]";
            sb.append(prefix).append(" [").append(mem.getRole()).append("] ");
            sb.append(mem.getSummary() != null ? mem.getSummary() : truncate(mem.getContent(), 100));
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== STM 操作 ====================

    @SuppressWarnings("unchecked")
    public List<MemoryItem> getShortTerm(String sessionId) {
        List<MemoryItem> items = (List<MemoryItem>) shortTermMemory.getIfPresent(sessionId);
        return items != null ? items : new ArrayList<>();
    }

    public void clearShortTerm(String sessionId) {
        shortTermMemory.invalidate(sessionId);
    }

    // ==================== LTM 操作 ====================

    public List<MemoryItem> getLongTerm(String sessionId) {
        return longTermMemory.getOrDefault(sessionId, new ArrayList<>());
    }

    // ==================== 持久化 ====================

    private synchronized void persistLongTermMemory() {
        try {
            Path path = Paths.get(memoryConfig.getStoragePath(), "long_term_memory.json");
            Path tmpPath = Paths.get(memoryConfig.getStoragePath(), "long_term_memory.json.tmp");
            Files.createDirectories(path.getParent());
            List<MemoryItem> allItems = longTermMemory.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(allItems);
            Files.writeString(tmpPath, json, StandardCharsets.UTF_8);
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("持久化长期记忆失败", e);
        }
    }

    private void loadLongTermMemory() {
        // 优先从 Qdrant 恢复
        if (qdrantService.isUsingRealQdrant()) {
            try {
                String collection = memoryConfig.getMemoryCollection();
                List<Map<String, Object>> allMeta = qdrantService.getAllMetadata(collection);
                if (allMeta != null && !allMeta.isEmpty()) {
                    for (Map<String, Object> meta : allMeta) {
                        String sessionId = (String) meta.get("sessionId");
                        if (sessionId == null) continue;
                        MemoryItem item = MemoryItem.builder()
                                .id((String) meta.getOrDefault("chunkId", UUID.randomUUID().toString()))
                                .sessionId(sessionId)
                                .role((String) meta.getOrDefault("role", "user"))
                                .content((String) meta.getOrDefault("content", ""))
                                .summary((String) meta.getOrDefault("summary", ""))
                                .timestamp(meta.get("timestamp") instanceof Number
                                        ? ((Number) meta.get("timestamp")).longValue() : 0)
                                .importance(meta.get("importance") instanceof Number
                                        ? ((Number) meta.get("importance")).doubleValue() : 0.5)
                                .type("LONG_TERM")
                                .build();
                        longTermMemory.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(item);
                        globalLongTermIndex.put(item.getId(), item);
                    }
                    log.info("已从 Qdrant 加载 {} 条持久化记忆", globalLongTermIndex.size());
                    return;
                }
            } catch (Exception e) {
                log.debug("Qdrant 记忆恢复失败，尝试 JSON 文件: {}", e.getMessage());
            }
        }

        // JSON 文件回退
        try {
            Path path = Paths.get(memoryConfig.getStoragePath(), "long_term_memory.json");
            if (!Files.exists(path)) return;

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
            log.info("已从 JSON 文件加载 {} 条持久化记忆", globalLongTermIndex.size());

            // 将 JSON 中的记忆回填到 Qdrant
            if (qdrantService.isUsingRealQdrant()) {
                for (MemoryItem memItem : globalLongTermIndex.values()) {
                    persistToQdrant(memItem);
                }
                log.info("JSON 记忆已回填到 Qdrant");
            }

        } catch (Exception e) {
            log.error("加载持久化记忆失败", e);
        }
    }

    // ==================== 工具方法 ====================

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
        // 在句子边界截断
        int cut = 50;
        for (int i = cut; i > Math.max(cut - 20, 0); i--) {
            char c = content.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                return content.substring(0, i + 1);
            }
        }
        return content.substring(0, 50) + "...";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 带分数的记忆条目（内部排序用） */
    private record ScoredMemory(MemoryItem item, double score) {}
}
