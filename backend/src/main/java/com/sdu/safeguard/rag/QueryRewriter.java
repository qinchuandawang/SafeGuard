package com.sdu.safeguard.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 查询改写 — 将用户短查询扩展为更适合向量搜索的形式。
 * 基于规则实现，无需额外 LLM 调用。
 *
 * 改写后的 query 用于向量搜索（提升语义召回），原始 query 用于关键词匹配。
 */
@Slf4j
@Component
public class QueryRewriter {

    /** 诈骗类型 → 同义扩展词 */
    private static final Map<String, List<String>> SCAM_TYPE_EXPANSIONS = new LinkedHashMap<>();

    /** 用户高频问法的分类映射 */
    private static final Map<String, List<String>> INTENT_EXPANSIONS = new LinkedHashMap<>();

    static {
        SCAM_TYPE_EXPANSIONS.put("冒充公检法", List.of("安全账户", "资金核查", "涉嫌犯罪", "通缉令", "洗钱",
                "冒充警察", "冒充法院", "配合调查", "加密通话", "保密案件"));
        SCAM_TYPE_EXPANSIONS.put("冒充客服", List.of("退款", "理赔", "注销会员", "商品质量问题",
                "快递丢失", "补偿", "店铺保障", "备用金"));
        SCAM_TYPE_EXPANSIONS.put("冒充熟人", List.of("借钱", "急用", "受伤", "住院", "送礼",
                "帮忙转账", "代付", "新号码"));
        SCAM_TYPE_EXPANSIONS.put("刷单返利", List.of("兼职", "日结", "佣金", "垫付", "任务",
                "点赞员", "好评", "信誉", "刷流水"));
        SCAM_TYPE_EXPANSIONS.put("投资理财", List.of("高回报", "稳赚", "炒股", "基金", "期货",
                "虚拟币", "数字货币", "外汇", "私募", "原始股"));
        SCAM_TYPE_EXPANSIONS.put("杀猪盘", List.of("交友", "恋爱", "网恋", "投资", "内幕消息",
                "博彩", "漏洞", "高收益"));
        SCAM_TYPE_EXPANSIONS.put("贷款诈骗", List.of("低息", "无抵押", "快速放款", "预付利息",
                "验证还款能力", "解冻", "额度"));
        SCAM_TYPE_EXPANSIONS.put("虚假购物", List.of("低价", "限量", "私下交易", "绕过平台",
                "定金", "海关费", "清关"));
        SCAM_TYPE_EXPANSIONS.put("机票退改签", List.of("航班取消", "延误赔偿", "改签", "保险理赔",
                "手续费", "客服电话"));
        SCAM_TYPE_EXPANSIONS.put("注销贷款账户", List.of("校园贷", "注销账号", "清空额度",
                "影响征信", "利率调整", "银监会"));

        INTENT_EXPANSIONS.put("被骗了怎么办", List.of("被骗后报警流程", "诈骗补救措施", "冻结账户",
                "报警电话", "110", "96110", "证据保存", "维权途径"));
        INTENT_EXPANSIONS.put("如何识别诈骗", List.of("防骗技巧", "识别方法", "诈骗特征",
                "防范指南", "注意事项", "自我保护"));
        INTENT_EXPANSIONS.put("被威胁", List.of("恐吓", "威胁", "勒索", "人身安全",
                "紧急报警", "求助"));
        INTENT_EXPANSIONS.put("已经汇款", List.of("被骗转账后怎么办", "被骗钱款追回", "紧急止付",
                "冻结骗子账户", "报警", "反诈中心"));
        INTENT_EXPANSIONS.put("收到可疑信息", List.of("信息核实", "真伪辨别", "官方渠道",
                "举报", "咨询", "确认"));
        INTENT_EXPANSIONS.put("想举报", List.of("举报诈骗", "12321", "12377", "反诈APP",
                "报警", "投诉"));
    }

    /** 否定/疑问词 — 命中时降低扩展强度 */
    private static final List<String> NEGATION_WORDS = List.of(
            "不是", "没有", "是否", "会不会", "能不能", "是不是", "吗",
            "并非", "并非", "不是", "不是吧"
    );

    /**
     * 改写查询：检测意图/诈骗类型并扩展。
     */
    public RewriteResult rewrite(String original) {
        if (original == null || original.isBlank()) {
            return new RewriteResult(original, original, List.of());
        }

        String lower = original.toLowerCase().trim();
        List<String> expansions = new ArrayList<>();

        // 检测否定/疑问语气 — 命中时降低扩展强度
        boolean hasNegation = NEGATION_WORDS.stream().anyMatch(lower::contains);

        // 1. 意图匹配
        if (!hasNegation) {
            for (Map.Entry<String, List<String>> entry : INTENT_EXPANSIONS.entrySet()) {
                if (containsAllKeywords(lower, entry.getKey())) {
                    expansions.addAll(entry.getValue());
                }
            }
        }

        // 2. 诈骗类型扩展（否定模式下也保留但减少数量）
        for (Map.Entry<String, List<String>> entry : SCAM_TYPE_EXPANSIONS.entrySet()) {
            if (containsAllKeywords(lower, entry.getKey())) {
                if (hasNegation) {
                    // 否定模式下只取前 3 个扩展词
                    expansions.addAll(entry.getValue().subList(0, Math.min(3, entry.getValue().size())));
                } else {
                    expansions.addAll(entry.getValue());
                }
            }
        }

        // 3. 对短查询（<8字），主动追加通用防骗扩展
        if (expansions.isEmpty() && original.length() < 8) {
            expansions.add("防诈骗");
            expansions.add("识别方法");
            expansions.add("防范");
        }

        // 构建扩展后的查询
        String rewritten;
        if (expansions.isEmpty()) {
            rewritten = original;
        } else {
            Set<String> merged = new LinkedHashSet<>();
            merged.add(original);
            merged.addAll(expansions);
            rewritten = String.join(" ", merged);
        }

        log.debug("QueryRewriter: \"{}\" → \"{}\" (expansions: {}, negation: {})",
                original, rewritten, expansions.size(), hasNegation);

        return new RewriteResult(original, rewritten, new ArrayList<>(expansions));
    }

    /**
     * 检查 lower 文本是否包含目标短语的所有关键词。
     */
    private boolean containsAllKeywords(String lower, String phrase) {
        String[] keywords = phrase.split("[，,、\\s]+");
        int matchCount = 0;
        for (String kw : keywords) {
            if (kw.length() >= 2 && lower.contains(kw)) {
                matchCount++;
            }
        }
        // 至少匹配一半的关键词认为命中
        return matchCount >= Math.max(1, keywords.length / 2);
    }

    @lombok.Data
    public static class RewriteResult {
        private final String original;
        private final String rewritten;
        private final List<String> expansions;

        public boolean isRewritten() {
            return !original.equals(rewritten);
        }
    }
}