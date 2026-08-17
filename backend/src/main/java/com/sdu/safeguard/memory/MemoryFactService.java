package com.sdu.safeguard.memory;

import com.sdu.safeguard.dto.MemoryItem;
import com.sdu.safeguard.entity.MemoryFactEvent;
import com.sdu.safeguard.mapper.MemoryFactEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MemoryFactService {

    private static final Set<String> CONFIRMED_TAGS = Set.of(
            "confirmed", "user_confirmed", "tool_verified");

    private final MemoryFactEventMapper mapper;

    /**
     * 在数据库事务中记录不可变事实事件，并维护当前有效版本。
     */
    @Transactional
    public FactWriteResult record(MemoryItem item) {
        FactDescriptor descriptor = extractFact(item.getContent());
        String ownerKey = sha256(item.getSessionId());
        MemoryFactEvent latest = mapper.findLatestForUpdate(ownerKey, descriptor.factKey());
        MemoryFactEvent active = mapper.findActiveForUpdate(ownerKey, descriptor.factKey());
        int version = latest == null ? 1 : latest.getVersion() + 1;
        boolean confirmed = hasConfirmedTag(item.getTags());
        String source = resolveSource(item, confirmed);
        String status;
        String supersedesId = null;

        // 未确认的同值陈述只做幂等去重；确认信息即使同值也要形成新版本，
        // 以便提升来源可信度并留下可审计的确认事件。
        if (active != null
                && active.getFactValue().equals(descriptor.factValue())
                && !confirmed) {
            status = "DUPLICATE";
            MemoryFactEvent duplicate = buildEvent(
                    item, descriptor, ownerKey, version, status, source, active.getEventId());
            mapper.insert(duplicate);
            return new FactWriteResult(toMemoryItem(active), null, false, false);
        }

        if (active != null && !active.getFactValue().equals(descriptor.factValue()) && !confirmed) {
            mapper.updateUnresolvedStatus(ownerKey, descriptor.factKey(), "CONFLICTED");
            status = "CONFLICTED";
            supersedesId = active.getEventId();
            MemoryFactEvent conflict = buildEvent(
                    item, descriptor, ownerKey, version, status, source, supersedesId);
            mapper.insert(conflict);
            return new FactWriteResult(null, active.getEventId(), true, false);
        }

        // 事实一旦出现矛盾，在确认或工具核验前不能被普通陈述重新激活。
        // 否则连续的用户陈述会把 CONFLICTED 错误地提升为 ACTIVE。
        if (active == null
                && latest != null
                && "CONFLICTED".equals(latest.getStatus())
                && !confirmed) {
            status = "CONFLICTED";
            supersedesId = latest.getEventId();
            MemoryFactEvent conflict = buildEvent(
                    item, descriptor, ownerKey, version, status, source, supersedesId);
            mapper.insert(conflict);
            return new FactWriteResult(null, latest.getEventId(), true, false);
        }

        if (confirmed && latest != null) {
            mapper.updateUnresolvedStatus(ownerKey, descriptor.factKey(), "SUPERSEDED");
            supersedesId = latest.getEventId();
        } else if (active != null) {
            mapper.updateUnresolvedStatus(ownerKey, descriptor.factKey(), "SUPERSEDED");
            supersedesId = active.getEventId();
        }

        status = "ACTIVE";
        MemoryFactEvent current = buildEvent(
                item, descriptor, ownerKey, version, status, source, supersedesId);
        mapper.insert(current);
        return new FactWriteResult(toMemoryItem(current), supersedesId, false, true);
    }

    public List<MemoryItem> loadActiveMemories() {
        return mapper.findAllActive().stream().map(this::toMemoryItem).toList();
    }

    private MemoryFactEvent buildEvent(MemoryItem item, FactDescriptor descriptor,
                                       String ownerKey, int version, String status,
                                       String source, String supersedesId) {
        return MemoryFactEvent.builder()
                .eventId(item.getId())
                .sessionId(item.getSessionId())
                .ownerKey(ownerKey)
                .factKey(descriptor.factKey())
                .factType(descriptor.factType())
                .factValue(descriptor.factValue())
                .content(item.getContent())
                .summary(generateFactSummary(descriptor, item.getContent()))
                .source(source)
                .confidence(hasConfirmedTag(item.getTags()) ? 1.0 : Math.min(item.getImportance(), 0.95))
                .importance(item.getImportance())
                .version(version)
                .status(status)
                .supersedesEventId(supersedesId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private MemoryItem toMemoryItem(MemoryFactEvent event) {
        return MemoryItem.builder()
                .id(event.getEventId())
                .sessionId(event.getSessionId())
                .role("user")
                .content(event.getContent())
                .timestamp(event.getCreatedAt() == null
                        ? 0 : event.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                .type("LONG_TERM")
                .importance(event.getImportance() == null ? 0.5 : event.getImportance())
                .summary(event.getSummary())
                .factKey(event.getFactKey())
                .factType(event.getFactType())
                .factValue(event.getFactValue())
                .version(event.getVersion())
                .status(event.getStatus())
                .source(event.getSource())
                .confidence(event.getConfidence())
                .supersedesId(event.getSupersedesEventId())
                .build();
    }

    private FactDescriptor extractFact(String content) {
        String normalized = normalize(content);
        boolean firstPerson = normalized.contains("我") || normalized.contains("本人");
        if (firstPerson && containsAny(normalized, "转账", "转了", "汇款")) {
            return new FactDescriptor("TRANSFER_STATUS", "transfer-status",
                    containsAny(normalized, "没转", "未转", "没有转", "尚未转")
                            ? "NOT_TRANSFERRED" : "TRANSFERRED");
        }
        if (firstPerson && normalized.contains("验证码")) {
            return new FactDescriptor("VERIFICATION_CODE_STATUS", "verification-code-status",
                    containsAny(normalized, "没给", "未给", "没有提供", "未提供")
                            ? "NOT_SHARED" : "SHARED");
        }
        if (firstPerson && containsAny(normalized, "屏幕共享", "共享屏幕")) {
            return new FactDescriptor("SCREEN_SHARE_STATUS", "screen-share-status",
                    containsAny(normalized, "没开", "未开", "没有开启", "已关闭")
                            ? "NOT_ENABLED" : "ENABLED");
        }
        if (firstPerson && containsAny(normalized, "报警", "报案")) {
            return new FactDescriptor("POLICE_REPORT_STATUS", "police-report-status",
                    containsAny(normalized, "没报警", "未报警", "没有报案")
                            ? "NOT_REPORTED" : "REPORTED");
        }
        String value = normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
        return new FactDescriptor("RISK_EVENT", "event-" + sha256(value), value);
    }

    private String resolveSource(MemoryItem item, boolean confirmed) {
        if (item.getTags() != null && item.getTags().contains("tool_verified")) return "TOOL_VERIFIED";
        if (confirmed) return "USER_CONFIRMED";
        return "USER_CLAIM";
    }

    private boolean hasConfirmedTag(List<String> tags) {
        if (tags == null) return false;
        return tags.stream().map(tag -> tag.toLowerCase(Locale.ROOT)).anyMatch(CONFIRMED_TAGS::contains);
    }

    private boolean containsAny(String content, String... values) {
        for (String value : values) if (content.contains(value)) return true;
        return false;
    }

    private String normalize(String content) {
        if (content == null) return "";
        return content.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 关系型存储保留原始陈述，向量库只嵌入面向检索的规范化事实摘要。 */
    private String generateFactSummary(FactDescriptor descriptor, String content) {
        return switch (descriptor.factType()) {
            case "TRANSFER_STATUS" -> "TRANSFERRED".equals(descriptor.factValue())
                    ? "用户已向可疑对象转账" : "用户尚未向可疑对象转账";
            case "VERIFICATION_CODE_STATUS" -> "SHARED".equals(descriptor.factValue())
                    ? "用户已提供验证码" : "用户尚未提供验证码";
            case "SCREEN_SHARE_STATUS" -> "ENABLED".equals(descriptor.factValue())
                    ? "用户已开启屏幕共享" : "用户未开启屏幕共享";
            case "POLICE_REPORT_STATUS" -> "REPORTED".equals(descriptor.factValue())
                    ? "用户已报警或报案" : "用户尚未报警或报案";
            default -> "用户风险事件：" + generateFallbackSummary(content);
        };
    }

    private String generateFallbackSummary(String content) {
        if (content == null || content.isBlank()) return "";
        return content.length() <= 100 ? content : content.substring(0, 100) + "...";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", impossible);
        }
    }

    private record FactDescriptor(String factType, String factKey, String factValue) {
    }

    public record FactWriteResult(MemoryItem activeMemory, String deactivatedEventId,
                                  boolean conflicted, boolean changed) {
    }
}
