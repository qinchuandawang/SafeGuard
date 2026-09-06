package com.sdu.safeguard.rag.agentic;

import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.rag.RAGService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识充分性反思（Reflection）— Agentic RAG 的"自检"环节。
 *
 * 规则版自检，确定性、零 LLM 成本：
 *  - 用查询命中的反诈关键词在返回块（content/tags）中的覆盖率衡量充分性；
 *  - 无关键词命中时退化到最大向量得分启发式，避免语义查询被误判为不充分。
 *
 * 输出 {@link Verdict}：是否充分 + 覆盖度 + 缺失知识面（供定向补检）。
 */
@Component
public class SufficiencyJudge {

    private final RAGService ragService;

    public SufficiencyJudge(RAGService ragService) {
        this.ragService = ragService;
    }

    public record Verdict(boolean sufficient, double coverage, List<String> missingFacets, double maxScore) {
    }

    /**
     * 判断返回结果是否覆盖查询所需知识。
     *
     * @param query   原始查询
     * @param results 当前轮检索结果
     * @param threshold 覆盖度阈值
     */
    public Verdict judge(String query, List<RagQueryResult> results, double threshold) {
        if (query == null || query.isBlank() || results == null || results.isEmpty()) {
            return new Verdict(false, 0.0, new ArrayList<>(), 0.0);
        }

        double maxScore = results.stream()
                .mapToDouble(r -> Math.max(r.getVectorScore(), r.getScore()))
                .max().orElse(0.0);

        Set<String> matchedKeywords = ragService.matchKeywords(query);
        if (matchedKeywords.isEmpty()) {
            // 无关键词查询：语义召回由向量得分决定
            double coverage = maxScore >= 0.35 ? 1.0 : Math.min(1.0, maxScore * 2.0);
            return new Verdict(coverage >= threshold, coverage, new ArrayList<>(), maxScore);
        }

        Set<String> covered = new LinkedHashSet<>();
        for (String keyword : matchedKeywords) {
            boolean hit = results.stream().anyMatch(r -> {
                if (r.getContent() != null && r.getContent().contains(keyword)) {
                    return true;
                }
                return r.getTags() != null && r.getTags().contains(keyword);
            });
            if (hit) {
                covered.add(keyword);
            }
        }

        List<String> missing = matchedKeywords.stream()
                .filter(kw -> !covered.contains(kw))
                .toList();
        double coverage = (double) covered.size() / matchedKeywords.size();
        boolean sufficient = coverage >= threshold;

        return new Verdict(sufficient, coverage, new ArrayList<>(missing), maxScore);
    }
}
