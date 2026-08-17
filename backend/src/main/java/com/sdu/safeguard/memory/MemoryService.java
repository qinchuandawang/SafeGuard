package com.sdu.safeguard.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.MemoryConfig;
import com.sdu.safeguard.dto.MemoryItem;
import com.sdu.safeguard.entity.ConversationMessage;
import com.sdu.safeguard.mapper.ConversationMessageMapper;
import com.sdu.safeguard.rag.EmbeddingService;
import com.sdu.safeguard.rag.QdrantService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private final ObjectProvider<MemoryFactService> factServiceProvider;
    private final ObjectProvider<ConversationMessageMapper> conversationMessageMapperProvider;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    private final Map<String, List<MemoryItem>> longTermMemory = new ConcurrentHashMap<>();
    private final Map<String, MemoryItem> globalLongTermIndex = new ConcurrentHashMap<>();

    private static final String SHORT_TERM_KEY_PREFIX = "safeguard:memory:stm:";
    private static final DefaultRedisScript<Long> APPEND_SHORT_TERM_SCRIPT = new DefaultRedisScript<>("""
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return redis.call('LLEN', KEYS[1])
            """, Long.class);
    private static final DefaultRedisScript<Long> HYDRATE_SHORT_TERM_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            for index = 1, #ARGV - 1 do
                redis.call('RPUSH', KEYS[1], ARGV[index])
            end
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[#ARGV]))
            return 1
            """, Long.class);
    /** Qdrant 记忆检索 Top-K */
    private static final int LTM_SEARCH_COUNT = 6;
    /** 最终返回的记忆上限 */
    private static final int FINAL_MEMORY_LIMIT = 10;
    /** 语义相关性为主，可信来源与时间新近度仅作为辅助排序信号。 */
    private static final double SEMANTIC_WEIGHT = 0.75;
    private static final double TRUST_WEIGHT = 0.20;
    private static final double RECENCY_WEIGHT = 0.05;
    private static final DateTimeFormatter MEMORY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public MemoryService(MemoryConfig memoryConfig,
                         @Qualifier("shortTermMemoryCache") Cache<String, Object> shortTermMemory,
                         ObjectMapper objectMapper,
                         EmbeddingService embeddingService,
                         QdrantService qdrantService,
                         ObjectProvider<MemoryFactService> factServiceProvider,
                         ObjectProvider<ConversationMessageMapper> conversationMessageMapperProvider,
                         ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.memoryConfig = memoryConfig;
        this.shortTermMemory = shortTermMemory;
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
        this.factServiceProvider = factServiceProvider;
        this.conversationMessageMapperProvider = conversationMessageMapperProvider;
        this.redisTemplateProvider = redisTemplateProvider;
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
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }

        // 先恢复活跃上下文，避免 Redis 过期后新消息覆盖此前的数据库历史。
        getShortTerm(sessionId);

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

        appendLocalShortTerm(sessionId, item);
        appendRedisShortTerm(sessionId, item);
        persistConversationMessage(item);

        // 重要内容晋升 LTM
        if (isLongTermCandidate(item)
                && item.getImportance() >= memoryConfig.getLongTermImportanceThreshold()) {
            addLongTerm(item);
        }
    }

    public void addLongTerm(MemoryItem item) {
        MemoryFactService factService = factServiceProvider.getIfAvailable();
        if (factService == null) {
            log.warn("长期记忆事实存储未配置，跳过写入，避免无版本控制污染向量库");
            return;
        }
        MemoryFactService.FactWriteResult factResult;
        try {
            factResult = factService.record(item);
        } catch (Exception exception) {
            log.error("长期记忆事实写入失败，跳过向量写入: sessionId={}, error={}",
                    item.getSessionId(), exception.getMessage());
            return;
        }

        if (factResult.deactivatedEventId() != null) {
            removeLocalLongTerm(factResult.deactivatedEventId());
            qdrantService.deleteFrom(factResult.deactivatedEventId(), memoryConfig.getMemoryCollection());
        }
        if (!factResult.changed() || factResult.activeMemory() == null) {
            return;
        }

        item = factResult.activeMemory();
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
                .summary(item.getSummary() != null && !item.getSummary().isBlank()
                        ? item.getSummary() : generateSummary(item.getContent()))
                .factKey(item.getFactKey())
                .factType(item.getFactType())
                .factValue(item.getFactValue())
                .version(item.getVersion())
                .status("ACTIVE")
                .source(item.getSource())
                .confidence(item.getConfidence())
                .supersedesId(item.getSupersedesId())
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
            String memorySummary = semanticMemoryText(item);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", item.getSessionId());
            // 用户记忆库只保存可检索的事实摘要；原始陈述保留在 MySQL 事实事件中。
            metadata.put("content", memorySummary);
            metadata.put("memoryEventId", item.getId());
            metadata.put("role", item.getRole());
            metadata.put("timestamp", item.getTimestamp());
            metadata.put("importance", item.getImportance());
            metadata.put("summary", memorySummary);
            metadata.put("tags", item.getTags() != null ? String.join(",", item.getTags()) : "");
            metadata.put("factKey", item.getFactKey() == null ? "" : item.getFactKey());
            metadata.put("factType", item.getFactType() == null ? "" : item.getFactType());
            metadata.put("factValue", item.getFactValue() == null ? "" : item.getFactValue());
            metadata.put("version", item.getVersion() == null ? 1 : item.getVersion());
            metadata.put("status", item.getStatus() == null ? "ACTIVE" : item.getStatus());
            metadata.put("source", item.getSource() == null ? "USER_CLAIM" : item.getSource());
            metadata.put("confidence", item.getConfidence() == null
                    ? sourceReliability(item.getSource()) : item.getConfidence());

            List<Float> vector = embeddingService.getEmbedding(memorySummary);
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

        if (query == null || query.isBlank()) {
            List<MemoryItem> all = new ArrayList<>(stm);
            all.addAll(ltm);
            return all;
        }

        Set<String> dedupKeys = new HashSet<>();
        List<ScoredMemory> scored = new ArrayList<>();

        // 1. 最近 N 条 STM (时序连续性)
        int recentCount = Math.min(memoryConfig.getShortTermContextSize(), stm.size());
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
            Map<String, String> filter = Map.of("sessionId", sessionId, "status", "ACTIVE");
            List<QdrantService.ScoredResult> qdrantResults = qdrantService.searchFrom(
                    queryVec, LTM_SEARCH_COUNT, filter, memoryConfig.getMemoryCollection());

            for (QdrantService.ScoredResult qr : qdrantResults) {
                String content = (String) qr.metadata.getOrDefault("content", "");
                if (content.isBlank() || !dedupKeys.add(content)) continue;

                MemoryItem item = MemoryItem.builder()
                        .id((String) qr.metadata.getOrDefault("memoryEventId", qr.chunkId))
                        .sessionId(sessionId)
                        .role((String) qr.metadata.getOrDefault("role", "assistant"))
                        .content(content)
                        .timestamp(qr.metadata.get("timestamp") instanceof Number
                                ? ((Number) qr.metadata.get("timestamp")).longValue() : 0)
                        .type("LONG_TERM")
                        .importance(0.5)
                        .summary((String) qr.metadata.getOrDefault("summary", content))
                        .factKey((String) qr.metadata.getOrDefault("factKey", ""))
                        .factType((String) qr.metadata.getOrDefault("factType", ""))
                        .factValue((String) qr.metadata.getOrDefault("factValue", ""))
                        .version(qr.metadata.get("version") instanceof Number
                                ? ((Number) qr.metadata.get("version")).intValue() : 1)
                        .status((String) qr.metadata.getOrDefault("status", "ACTIVE"))
                        .source((String) qr.metadata.getOrDefault("source", "USER_CLAIM"))
                        .confidence(readConfidence(qr.metadata))
                        .build();
                scored.add(new ScoredMemory(item, scoreLongTermMemory(item, qr.score)));
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
                                embeddingService.getEmbedding(semanticMemoryText(item)))
                        : 0.3;
                scored.add(new ScoredMemory(item, scoreLongTermMemory(item, score)));
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
            if ("LONG_TERM".equals(mem.getType())) {
                sb.append("[").append(sourceLabel(mem.getSource())).append("] ");
                if (mem.getConfidence() != null) {
                    sb.append("[可信度 ").append(String.format(Locale.ROOT, "%.2f", mem.getConfidence()))
                            .append("] ");
                }
                if (mem.getTimestamp() > 0) {
                    sb.append("[").append(formatMemoryTime(mem.getTimestamp())).append("] ");
                }
            }
            sb.append(mem.getSummary() != null ? mem.getSummary() : truncate(mem.getContent(), 100));
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== STM 操作 ====================

    public List<MemoryItem> getShortTerm(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return new ArrayList<>();
        List<MemoryItem> redisItems = loadRedisShortTerm(sessionId);
        if (redisItems != null) {
            shortTermMemory.put(sessionId,
                    Collections.synchronizedList(new ArrayList<>(redisItems)));
            return redisItems;
        }
        List<MemoryItem> localItems = localShortTermSnapshot(sessionId);
        if (!localItems.isEmpty()) {
            hydrateRedisShortTerm(sessionId, localItems);
            return localItems;
        }
        List<MemoryItem> persistedItems = loadPersistedShortTerm(sessionId,
                memoryConfig.getShortTermMaxSize());
        if (!persistedItems.isEmpty()) {
            shortTermMemory.put(sessionId,
                    Collections.synchronizedList(new ArrayList<>(persistedItems)));
            hydrateRedisShortTerm(sessionId, persistedItems);
        }
        return persistedItems;
    }

    public void clearShortTerm(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        shortTermMemory.invalidate(sessionId);
        StringRedisTemplate redis = redisTemplate();
        if (redis != null) {
            try {
                redis.delete(shortTermKey(sessionId));
            } catch (Exception e) {
                log.warn("清理 Redis 短期记忆失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
        ConversationMessageMapper mapper = conversationMessageMapperProvider.getIfAvailable();
        if (mapper != null) {
            try {
                mapper.deleteByConversationId(sessionId);
            } catch (Exception e) {
                log.warn("清理持久化会话历史失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
    }

    /** 前端历史记录与 Redis 无关，始终从 MySQL 分页读取。 */
    public List<MemoryItem> getConversationHistory(String sessionId, int limit) {
        return getConversationHistoryPage(sessionId, limit, null).messages();
    }

    /** 使用主键游标分页读取会话历史，消息按时间正序返回。 */
    public ConversationHistoryPage getConversationHistoryPage(String sessionId, int limit, Long beforeId) {
        if (sessionId == null || sessionId.isBlank()) return new ConversationHistoryPage(List.of(), null);
        int pageSize = Math.min(Math.max(limit, 1), 100);
        List<ConversationMessage> messages = loadPersistedMessages(sessionId, pageSize, beforeId);
        Long nextBeforeId = messages.size() == pageSize
                ? messages.get(messages.size() - 1).getId() : null;
        return new ConversationHistoryPage(toMemoryItems(messages), nextBeforeId);
    }

    @SuppressWarnings("unchecked")
    private void appendLocalShortTerm(String sessionId, MemoryItem item) {
        List<MemoryItem> items = (List<MemoryItem>) shortTermMemory.get(
                sessionId, key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (items) {
            items.add(item);
            int overflow = items.size() - memoryConfig.getShortTermMaxSize();
            if (overflow > 0) items.subList(0, overflow).clear();
        }
    }

    @SuppressWarnings("unchecked")
    private List<MemoryItem> localShortTermSnapshot(String sessionId) {
        List<MemoryItem> items = (List<MemoryItem>) shortTermMemory.getIfPresent(sessionId);
        if (items == null) return new ArrayList<>();
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    private void appendRedisShortTerm(String sessionId, MemoryItem item) {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null) return;
        try {
            redis.execute(APPEND_SHORT_TERM_SCRIPT, List.of(shortTermKey(sessionId)),
                    objectMapper.writeValueAsString(item),
                    String.valueOf(memoryConfig.getShortTermMaxSize()),
                    String.valueOf(Duration.ofHours(memoryConfig.getShortTermTtlHours()).toSeconds()));
        } catch (Exception e) {
            log.warn("写入 Redis 短期记忆失败，保留本地副本: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    private List<MemoryItem> loadRedisShortTerm(String sessionId) {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null) return null;
        try {
            List<String> values = redis.opsForList().range(shortTermKey(sessionId), 0, -1);
            if (values == null || values.isEmpty()) return null;
            List<MemoryItem> items = new ArrayList<>(values.size());
            for (String value : values) {
                try {
                    items.add(objectMapper.readValue(value, MemoryItem.class));
                } catch (Exception malformed) {
                    log.warn("忽略损坏的短期记忆条目: sessionId={}", sessionId);
                }
            }
            return items;
        } catch (Exception e) {
            log.warn("读取 Redis 短期记忆失败，回退本地副本: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    private StringRedisTemplate redisTemplate() {
        return redisEnabled ? redisTemplateProvider.getIfAvailable() : null;
    }

    /** Redis 键不存在时，由数据库或本地副本一次性回填最近上下文。 */
    private void hydrateRedisShortTerm(String sessionId, List<MemoryItem> items) {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null || items == null || items.isEmpty()) return;
        try {
            List<String> arguments = new ArrayList<>(items.size() + 1);
            for (MemoryItem item : items) {
                arguments.add(objectMapper.writeValueAsString(item));
            }
            arguments.add(String.valueOf(Duration.ofHours(memoryConfig.getShortTermTtlHours()).toSeconds()));
            redis.execute(HYDRATE_SHORT_TERM_SCRIPT, List.of(shortTermKey(sessionId)), arguments.toArray());
        } catch (Exception e) {
            log.warn("回填 Redis 短期记忆失败，继续使用持久化历史: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    private void persistConversationMessage(MemoryItem item) {
        ConversationMessageMapper mapper = conversationMessageMapperProvider.getIfAvailable();
        if (mapper == null) {
            log.warn("会话历史 Mapper 未配置，短期记忆仅保留在缓存中: sessionId={}", item.getSessionId());
            return;
        }
        try {
            mapper.insert(ConversationMessage.builder()
                    .messageId(item.getId())
                    .conversationId(item.getSessionId())
                    .role(item.getRole())
                    .content(item.getContent())
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.error("持久化会话消息失败，缓存仍可服务当前请求: sessionId={}, messageId={}, error={}",
                    item.getSessionId(), item.getId(), e.getMessage());
        }
    }

    private List<MemoryItem> loadPersistedShortTerm(String sessionId, int limit) {
        return toMemoryItems(loadPersistedMessages(sessionId, limit, null));
    }

    private List<ConversationMessage> loadPersistedMessages(String sessionId, int limit, Long beforeId) {
        ConversationMessageMapper mapper = conversationMessageMapperProvider.getIfAvailable();
        if (mapper == null) return List.of();
        try {
            List<ConversationMessage> messages = mapper.findRecentBefore(sessionId, beforeId, limit);
            return messages == null ? List.of() : messages;
        } catch (Exception e) {
            log.warn("读取持久化会话历史失败，返回缓存上下文: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return List.of();
        }
    }

    private List<MemoryItem> toMemoryItems(List<ConversationMessage> messages) {
        return messages.stream()
                .sorted(Comparator.comparing(ConversationMessage::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(message -> MemoryItem.builder()
                        .id(message.getMessageId())
                        .sessionId(message.getConversationId())
                        .role(message.getRole())
                        .content(message.getContent())
                        .timestamp(message.getCreatedAt() == null ? 0
                                : message.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli())
                        .type("SHORT_TERM")
                        .importance(computeImportance(message.getContent()))
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String shortTermKey(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return SHORT_TERM_KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", impossible);
        }
    }

    /**
     * 时间衰减只参与排序，不参与事实覆盖；事实冲突仍必须由状态机和来源可信度处理。
     */
    private double scoreLongTermMemory(MemoryItem item, double semanticScore) {
        return semanticScore * SEMANTIC_WEIGHT
                + effectiveTrust(item) * TRUST_WEIGHT
                + recencyScore(item.getTimestamp()) * RECENCY_WEIGHT;
    }

    private String semanticMemoryText(MemoryItem item) {
        if (item.getSummary() != null && !item.getSummary().isBlank()) {
            return item.getSummary();
        }
        return item.getContent() == null ? "" : item.getContent();
    }

    private double effectiveTrust(MemoryItem item) {
        double sourceTrust = sourceReliability(item.getSource());
        if (item.getConfidence() == null) return sourceTrust;
        return Math.min(Math.max(item.getConfidence(), 0.0), sourceTrust);
    }

    private double sourceReliability(String source) {
        if ("TOOL_VERIFIED".equals(source)) return 1.0;
        if ("USER_CONFIRMED".equals(source)) return 0.9;
        return 0.6;
    }

    private Double readConfidence(Map<String, Object> metadata) {
        Object raw = metadata.get("confidence");
        if (raw instanceof Number number) return number.doubleValue();
        return sourceReliability((String) metadata.getOrDefault("source", "USER_CLAIM"));
    }

    private double recencyScore(long timestamp) {
        if (timestamp <= 0) return 0.0;
        double ageDays = Math.max(0, Instant.now().toEpochMilli() - timestamp) / 86_400_000.0;
        // 半衰期为 30 天，避免短期状态因时间过久长期占据上下文。
        return Math.pow(0.5, ageDays / 30.0);
    }

    private String sourceLabel(String source) {
        if ("TOOL_VERIFIED".equals(source)) return "工具核验";
        if ("USER_CONFIRMED".equals(source)) return "用户确认";
        return "用户自述";
    }

    private String formatMemoryTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                .format(MEMORY_TIME_FORMATTER);
    }

    // ==================== LTM 操作 ====================

    public List<MemoryItem> getLongTerm(String sessionId) {
        return longTermMemory.getOrDefault(sessionId, new ArrayList<>());
    }

    private void removeLocalLongTerm(String eventId) {
        longTermMemory.values().forEach(items -> items.removeIf(item -> eventId.equals(item.getId())));
        globalLongTermIndex.remove(eventId);
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
        MemoryFactService factService = factServiceProvider.getIfAvailable();
        if (factService != null) {
            try {
                List<MemoryItem> activeFacts = factService.loadActiveMemories();
                if (!activeFacts.isEmpty()) {
                    activeFacts.forEach(item -> {
                        registerLocalLongTerm(item);
                        // Collection 版本切换或 Qdrant 重建后，从 MySQL 事实源恢复摘要向量。
                        persistToQdrant(item);
                    });
                    log.info("已从 MySQL 加载 {} 条 ACTIVE 长期事实", activeFacts.size());
                    return;
                }
            } catch (Exception exception) {
                log.warn("加载 MySQL 长期事实失败，尝试兼容旧 Qdrant 数据: {}", exception.getMessage());
            }
        }
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

    private void registerLocalLongTerm(MemoryItem item) {
        longTermMemory.computeIfAbsent(item.getSessionId(), key -> new ArrayList<>()).add(item);
        globalLongTermIndex.put(item.getId(), item);
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
        if (containsAny(content, "转账", "验证码", "被骗", "损失", "屏幕共享", "安全账户")) {
            score += 0.3;
        }
        return Math.min(score, 1.0);
    }

    private boolean isLongTermCandidate(MemoryItem item) {
        if ("user".equalsIgnoreCase(item.getRole())) return true;
        if (item.getTags() == null) return false;
        return item.getTags().stream().anyMatch(tag ->
                "tool_verified".equalsIgnoreCase(tag) || "confirmed".equalsIgnoreCase(tag));
    }

    private boolean containsAny(String content, String... values) {
        if (content == null) return false;
        for (String value : values) if (content.contains(value)) return true;
        return false;
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

    public record ConversationHistoryPage(List<MemoryItem> messages, Long nextBeforeId) {}
}
