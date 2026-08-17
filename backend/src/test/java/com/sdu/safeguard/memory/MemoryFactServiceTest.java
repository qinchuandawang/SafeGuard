package com.sdu.safeguard.memory;

import com.sdu.safeguard.dto.MemoryItem;
import com.sdu.safeguard.entity.MemoryFactEvent;
import com.sdu.safeguard.mapper.MemoryFactEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryFactServiceTest {

    private final MemoryFactEventMapper mapper = mock(MemoryFactEventMapper.class);
    private final MemoryFactService service = new MemoryFactService(mapper);

    @Test
    @DisplayName("首次事实写入为 ACTIVE 版本")
    void shouldCreateActiveFact() {
        when(mapper.findLatestForUpdate(any(), any())).thenReturn(null);
        when(mapper.findActiveForUpdate(any(), any())).thenReturn(null);

        MemoryFactService.FactWriteResult result = service.record(item("我已经转账5000元", List.of()));

        assertTrue(result.changed());
        assertEquals("TRANSFERRED", result.activeMemory().getFactValue());
        ArgumentCaptor<MemoryFactEvent> captor = ArgumentCaptor.forClass(MemoryFactEvent.class);
        verify(mapper).insert(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        assertEquals("用户已向可疑对象转账", captor.getValue().getSummary());
    }

    @Test
    @DisplayName("相反用户陈述不会覆盖旧事实而是标记冲突")
    void shouldMarkContradictoryClaimsAsConflicted() {
        MemoryFactEvent old = event("old-event", "TRANSFERRED", 1, "ACTIVE");
        when(mapper.findLatestForUpdate(any(), any())).thenReturn(old);
        when(mapper.findActiveForUpdate(any(), any())).thenReturn(old);

        MemoryFactService.FactWriteResult result = service.record(item("我没有转账", List.of()));

        assertTrue(result.conflicted());
        assertTrue(result.activeMemory() == null);
        verify(mapper).updateUnresolvedStatus(any(), any(), eq("CONFLICTED"));
    }

    @Test
    @DisplayName("明确确认的新事实可以替代旧版本")
    void shouldSupersedeOldFactWhenConfirmed() {
        MemoryFactEvent old = event("old-event", "TRANSFERRED", 1, "CONFLICTED");
        when(mapper.findLatestForUpdate(any(), any())).thenReturn(old);
        when(mapper.findActiveForUpdate(any(), any())).thenReturn(null);

        MemoryFactService.FactWriteResult result = service.record(
                item("我没有转账", List.of("confirmed")));

        assertTrue(result.changed());
        assertEquals("NOT_TRANSFERRED", result.activeMemory().getFactValue());
        verify(mapper).updateUnresolvedStatus(any(), any(), eq("SUPERSEDED"));
    }

    @Test
    @DisplayName("同值确认会创建新版本并提升为用户确认来源")
    void shouldCreateConfirmedVersionForSameValue() {
        MemoryFactEvent old = event("old-event", "TRANSFERRED", 1, "ACTIVE");
        when(mapper.findLatestForUpdate(any(), any())).thenReturn(old);
        when(mapper.findActiveForUpdate(any(), any())).thenReturn(old);

        MemoryFactService.FactWriteResult result = service.record(
                item("我已经转账5000元", List.of("confirmed")));

        assertTrue(result.changed());
        assertEquals("USER_CONFIRMED", result.activeMemory().getSource());
        assertEquals(2, result.activeMemory().getVersion());
        verify(mapper).updateUnresolvedStatus(any(), any(), eq("SUPERSEDED"));
    }

    @Test
    @DisplayName("冲突状态下的普通陈述不能重新激活事实")
    void shouldKeepNewClaimConflictedAfterConflict() {
        MemoryFactEvent latest = event("conflict-event", "NOT_TRANSFERRED", 2, "CONFLICTED");
        when(mapper.findLatestForUpdate(any(), any())).thenReturn(latest);
        when(mapper.findActiveForUpdate(any(), any())).thenReturn(null);

        MemoryFactService.FactWriteResult result = service.record(
                item("我已经转账5000元", List.of()));

        assertTrue(result.conflicted());
        assertTrue(result.activeMemory() == null);
        ArgumentCaptor<MemoryFactEvent> captor = ArgumentCaptor.forClass(MemoryFactEvent.class);
        verify(mapper).insert(captor.capture());
        assertEquals("CONFLICTED", captor.getValue().getStatus());
        assertEquals("conflict-event", captor.getValue().getSupersedesEventId());
    }

    @Test
    @DisplayName("工具核验可以解除冲突并建立当前有效事实")
    void shouldResolveConflictWithToolVerification() {
        MemoryFactEvent latest = event("conflict-event", "NOT_TRANSFERRED", 2, "CONFLICTED");
        when(mapper.findLatestForUpdate(any(), any())).thenReturn(latest);
        when(mapper.findActiveForUpdate(any(), any())).thenReturn(null);

        MemoryFactService.FactWriteResult result = service.record(
                item("我已经转账5000元", List.of("tool_verified")));

        assertTrue(result.changed());
        assertEquals("ACTIVE", result.activeMemory().getStatus());
        assertEquals("TOOL_VERIFIED", result.activeMemory().getSource());
        verify(mapper).updateUnresolvedStatus(any(), any(), eq("SUPERSEDED"));
    }

    private MemoryItem item(String content, List<String> tags) {
        return MemoryItem.builder()
                .id("event-" + content.hashCode())
                .sessionId("conversation-1")
                .role("user")
                .content(content)
                .importance(0.8)
                .tags(tags)
                .build();
    }

    private MemoryFactEvent event(String id, String value, int version, String status) {
        return MemoryFactEvent.builder()
                .eventId(id)
                .sessionId("conversation-1")
                .factKey("transfer-status")
                .factType("TRANSFER_STATUS")
                .factValue(value)
                .content("历史陈述")
                .version(version)
                .status(status)
                .importance(0.8)
                .confidence(0.8)
                .build();
    }
}
