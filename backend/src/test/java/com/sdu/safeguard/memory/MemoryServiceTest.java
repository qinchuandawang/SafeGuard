package com.sdu.safeguard.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sdu.safeguard.config.MemoryConfig;
import com.sdu.safeguard.dto.MemoryItem;
import com.sdu.safeguard.entity.ConversationMessage;
import com.sdu.safeguard.mapper.ConversationMessageMapper;
import com.sdu.safeguard.rag.EmbeddingService;
import com.sdu.safeguard.rag.QdrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class MemoryServiceTest {

    private MemoryService memoryService;
    private ConversationMessageMapper conversationMessageMapper;
    private EmbeddingService embeddingService;
    private QdrantService qdrantService;
    private MemoryFactService factService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MemoryConfig config = new MemoryConfig();
        config.setShortTermMaxSize(5);
        config.setShortTermContextSize(3);
        config.setLongTermImportanceThreshold(1.0);
        Cache<String, Object> localCache = Caffeine.newBuilder().build();
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        ObjectProvider<ConversationMessageMapper> conversationMapperProvider = mock(ObjectProvider.class);
        conversationMessageMapper = mock(ConversationMessageMapper.class);
        embeddingService = mock(EmbeddingService.class);
        qdrantService = mock(QdrantService.class);
        factService = mock(MemoryFactService.class);
        ObjectProvider<MemoryFactService> factServiceProvider = mock(ObjectProvider.class);
        when(factServiceProvider.getIfAvailable()).thenReturn(factService);
        when(conversationMapperProvider.getIfAvailable()).thenReturn(conversationMessageMapper);
        memoryService = new MemoryService(
                config,
                localCache,
                new ObjectMapper(),
                embeddingService,
                qdrantService,
                factServiceProvider,
                conversationMapperProvider,
                redisProvider);
    }

    @Test
    @DisplayName("Redis关闭时使用本地副本并只保留最新记录")
    void shouldTrimLocalShortTermMemoryToConfiguredSize() {
        for (int index = 0; index < 8; index++) {
            memoryService.addShortTerm(
                    "conversation-1", "user", "普通消息-" + index, List.of("query"));
        }

        List<MemoryItem> memories = memoryService.getShortTerm("conversation-1");

        assertEquals(5, memories.size());
        assertEquals("普通消息-3", memories.get(0).getContent());
        assertEquals("普通消息-7", memories.get(4).getContent());
    }

    @Test
    @DisplayName("清理会话时同步清除本地短期记忆")
    void shouldClearLocalShortTermMemory() {
        memoryService.addShortTerm("conversation-2", "user", "测试消息", List.of());

        memoryService.clearShortTerm("conversation-2");

        assertTrue(memoryService.getShortTerm("conversation-2").isEmpty());
    }

    @Test
    @DisplayName("空会话和空内容不会写入短期记忆")
    void shouldIgnoreInvalidShortTermMemory() {
        memoryService.addShortTerm("", "user", "测试消息", List.of());
        memoryService.addShortTerm("conversation-3", "user", "", List.of());

        assertTrue(memoryService.getShortTerm("").isEmpty());
        assertTrue(memoryService.getShortTerm("conversation-3").isEmpty());
    }

    @Test
    @DisplayName("缓存未命中时从 MySQL 恢复最近会话上下文")
    void shouldRestoreRecentContextFromDatabaseWhenCacheMisses() {
        when(conversationMessageMapper.findRecentBefore("conversation-4", null, 5)).thenReturn(List.of(
                message(2L, "message-2", "assistant", "后续回复"),
                message(1L, "message-1", "user", "此前提问")));

        List<MemoryItem> memories = memoryService.getShortTerm("conversation-4");

        assertEquals(2, memories.size());
        assertEquals("此前提问", memories.get(0).getContent());
        assertEquals("后续回复", memories.get(1).getContent());
    }

    @Test
    @DisplayName("新消息写入缓存时同步持久化会话历史")
    void shouldPersistConversationMessage() {
        memoryService.addShortTerm("conversation-5", "user", "请分析这段话术", List.of("query"));

        verify(conversationMessageMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "conversation-5".equals(message.getConversationId())
                        && "user".equals(message.getRole())
                        && "请分析这段话术".equals(message.getContent())));
    }

    @Test
    @DisplayName("会话历史使用主键游标返回下一页位置")
    void shouldReturnCursorForConversationHistory() {
        when(conversationMessageMapper.findRecentBefore("conversation-4", null, 2)).thenReturn(List.of(
                message(3L, "message-3", "assistant", "最新回复"),
                message(2L, "message-2", "user", "最近提问")));

        MemoryService.ConversationHistoryPage page = memoryService
                .getConversationHistoryPage("conversation-4", 2, null);

        assertEquals(2L, page.nextBeforeId());
        assertEquals("最近提问", page.messages().get(0).getContent());
        assertEquals("最新回复", page.messages().get(1).getContent());
    }

    @Test
    @DisplayName("长期记忆检索会兼顾可信来源与时间新近度")
    void shouldRankTrustedActiveMemoryAheadOfUnverifiedClaim() {
        long now = Instant.now().toEpochMilli();
        when(embeddingService.getEmbedding("是否已经转账")).thenReturn(List.of(1.0F, 0.0F));
        when(qdrantService.searchFrom(List.of(1.0F, 0.0F), 6,
                Map.of("sessionId", "conversation-6", "status", "ACTIVE"), "user_memories_v2"))
                .thenReturn(List.of(
                        new QdrantService.ScoredResult("claim", 0.80, Map.of(
                                "content", "用户自述已经转账", "role", "user", "timestamp", now,
                                "status", "ACTIVE", "source", "USER_CLAIM", "confidence", 0.80)),
                        new QdrantService.ScoredResult("verified", 0.80, Map.of(
                                "content", "银行流水工具核验：尚未转账", "role", "tool",
                                "timestamp", now - 7L * 86_400_000L,
                                "status", "ACTIVE", "source", "TOOL_VERIFIED", "confidence", 1.0))));

        List<MemoryItem> memories = memoryService.retrieveRelevantMemory("conversation-6", "是否已经转账");

        assertEquals("银行流水工具核验：尚未转账", memories.get(0).getContent());
        String context = memoryService.formatMemoryContext("conversation-6", "是否已经转账");
        assertTrue(context.contains("[工具核验]"));
        assertTrue(context.contains("[可信度 1.00]"));
    }

    @Test
    @DisplayName("长期记忆仅将规范化摘要嵌入用户记忆库")
    void shouldEmbedFactSummaryInsteadOfOriginalStatement() {
        MemoryItem original = MemoryItem.builder()
                .id("event-1")
                .sessionId("conversation-7")
                .role("user")
                .content("对方冒充公安局，我在上午十点二十向对方账户转了五千元")
                .summary("用户确认已向可疑对象转账")
                .importance(0.9)
                .build();
        when(factService.record(original)).thenReturn(new MemoryFactService.FactWriteResult(
                original, null, false, true));
        when(embeddingService.getEmbedding("用户确认已向可疑对象转账"))
                .thenReturn(List.of(1.0F, 0.0F));

        memoryService.addLongTerm(original);

        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(qdrantService).insertTo(eq("event-1"), eq(List.of(1.0F, 0.0F)),
                metadataCaptor.capture(), eq("user_memories_v2"));
        assertEquals("用户确认已向可疑对象转账", metadataCaptor.getValue().get("content"));
        assertEquals("event-1", metadataCaptor.getValue().get("memoryEventId"));
    }

    private ConversationMessage message(Long id, String messageId, String role, String content) {
        return ConversationMessage.builder()
                .id(id)
                .messageId(messageId)
                .conversationId("conversation-4")
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.of(2026, 8, 17, 12, 0).plusSeconds(id))
                .build();
    }
}
