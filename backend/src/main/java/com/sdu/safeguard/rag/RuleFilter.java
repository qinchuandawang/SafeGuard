package com.sdu.safeguard.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdu.safeguard.config.RAGConfig;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 规则过滤层 — 从外部 JSON 配置加载欺诈规则。
 * 配置文件路径: classpath:config/rules.json
 * 可通过 {@link #reload()} 热加载。
 */
@Slf4j
@Component
public class RuleFilter {

    private List<FraudRule> rules = new ArrayList<>();
    private final RAGConfig ragConfig;
    private final ObjectMapper objectMapper;

    public RuleFilter(RAGConfig ragConfig, ObjectMapper objectMapper) {
        this.ragConfig = ragConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadRules();
    }

    public void loadRules() {
        String rulesPath = ragConfig.getRulesPath();
        if (rulesPath == null || rulesPath.isBlank()) {
            rulesPath = "config/rules.json";
        }
        try (InputStream is = new ClassPathResource(rulesPath).getInputStream()) {
            RuleFile ruleFile = objectMapper.readValue(is, RuleFile.class);
            if (ruleFile != null && ruleFile.getRules() != null && !ruleFile.getRules().isEmpty()) {
                rules = ruleFile.getRules();
                log.info("加载规则文件: {}, {} 条规则", rulesPath, rules.size());
            } else {
                log.warn("规则文件为空: {}", rulesPath);
                rules = new ArrayList<>();
            }
        } catch (Exception e) {
            log.error("规则文件加载失败: {}, 使用内置默认规则", rulesPath, e);
            loadDefaultRules();
        }
    }

    public void reload() {
        loadRules();
        log.info("规则热加载完成，当前 {} 条", rules.size());
    }

    private void loadDefaultRules() {
        rules = new ArrayList<>();
        for (String kw : List.of("转账","汇款","打款","验证码","银行卡号","密码","屏幕共享",
                "安全账户","资金核查","手续费","保证金","解冻费","共享屏幕","远程控制","下载APP")) {
            rules.add(new FraudRule(kw, 1.0, "DIRECT_ACTION"));
        }
        for (String kw : List.of("冒充","公检法","公安局","检察院","熟人","亲友","老板")) {
            rules.add(new FraudRule(kw, 0.9, "IMPERSONATION"));
        }
        for (String kw : List.of("高回报","稳赚不赔","内幕消息","月入过万","轻松赚钱")) {
            rules.add(new FraudRule(kw, 0.85, "INDUCEMENT"));
        }
        for (String kw : List.of("紧急","逾期","冻结","账户异常","影响征信","涉嫌犯罪")) {
            rules.add(new FraudRule(kw, 0.95, "URGENCY_PRESSURE"));
        }
        for (String kw : List.of("刷单","杀猪盘","投资诈骗","贷款诈骗","冒充客服","机票退改签")) {
            rules.add(new FraudRule(kw, 0.8, "SCAM_TYPE"));
        }
        log.warn("加载内置默认规则: {} 条", rules.size());
    }

    public RuleResult match(String text) {
        if (text == null || text.isBlank()) {
            return new RuleResult(false, 0.0, List.of(), List.of());
        }

        String lower = text.toLowerCase();
        Set<FraudRule> matched = new HashSet<>();

        for (FraudRule rule : rules) {
            if (lower.contains(rule.getKeyword())) {
                matched.add(rule);
            }
        }

        if (matched.isEmpty()) {
            return new RuleResult(false, 0.0, List.of(), List.of());
        }

        double maxScore = matched.stream().mapToDouble(FraudRule::getWeight).max().orElse(0.0);
        double avgScore = matched.stream().mapToDouble(FraudRule::getWeight).average().orElse(0.0);
        double finalScore = maxScore * 0.6 + avgScore * 0.4;

        List<String> matchedKeywords = matched.stream()
                .map(FraudRule::getKeyword)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        Set<String> categories = matched.stream()
                .map(FraudRule::getCategory)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new RuleResult(true, Math.min(finalScore, 1.0), matchedKeywords, new ArrayList<>(categories));
    }

    public String formatForPrompt(RuleResult result) {
        if (result == null || !result.isMatched()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【规则过滤结果】\n");
        sb.append("风险评分: ").append(String.format("%.2f", result.getRiskScore())).append("\n");
        sb.append("命中的欺诈关键词: ").append(String.join(", ", result.getMatchedKeywords())).append("\n");
        sb.append("涉及类别: ").append(String.join(", ", result.getCategories())).append("\n");
        return sb.toString();
    }

    @Data
    public static class FraudRule {
        private String keyword;
        private double weight;
        private String category;
        public FraudRule() {}
        public FraudRule(String keyword, double weight, String category) {
            this.keyword = keyword;
            this.weight = weight;
            this.category = category;
        }
    }

    @Data
    public static class RuleFile {
        private List<FraudRule> rules;
    }

    @Data
    public static class RuleResult {
        private final boolean matched;
        private final double riskScore;
        private final List<String> matchedKeywords;
        private final List<String> categories;
    }
}
