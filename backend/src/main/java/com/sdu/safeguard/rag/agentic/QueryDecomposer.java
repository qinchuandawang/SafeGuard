package com.sdu.safeguard.rag.agentic;

import com.sdu.safeguard.rag.QueryRewriter;
import com.sdu.safeguard.rag.RAGService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询分解器 — Agentic RAG 的"分析/计划"环节。
 *
 * 将一条用户问题解析为可执行的知识面（facets）：
 *  - 规则命中的反诈关键词（来自 {@link RAGService#matchKeywords}）
 *  - 同义扩展词（来自 {@link QueryRewriter#collectExpansions}）
 *
 * 多意图问题（如"刷单被骗后如何报警和止损"）会被拆出多个知识面，
 * 供后续定向补检针对缺失面检索，而不是用整条长查询一次性碰运气。
 */
@Component
public class QueryDecomposer {

    private final RAGService ragService;
    private final QueryRewriter queryRewriter;

    public QueryDecomposer(RAGService ragService, QueryRewriter queryRewriter) {
        this.ragService = ragService;
        this.queryRewriter = queryRewriter;
    }

    /** 单个知识面：命中的关键词 + 同义扩展 */
    public record Facet(String keyword, List<String> expansions) {
    }

    /**
     * 分解查询：先命中的欺诈关键词作为主知识面，再补同义扩展词。
     * 不保证与查询原词重叠的扩展词被去重后的顺序稳定。
     */
    public List<Facet> decompose(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> matchedKeywords = ragService.matchKeywords(query);
        List<String> expansions = queryRewriter.collectExpansions(query);

        List<Facet> facets = new ArrayList<>();
        if (matchedKeywords.isEmpty()) {
            // 无明确关键词时，把同义扩展作为唯一知识面
            if (!expansions.isEmpty()) {
                facets.add(new Facet(query, expansions));
            }
            return facets;
        }

        for (String keyword : matchedKeywords) {
            List<String> related = expansions.stream()
                    .filter(exp -> !exp.equals(keyword))
                    .toList();
            facets.add(new Facet(keyword, related));
        }
        return facets;
    }

    /**
     * 基于反思缺失面构建补检查询：
     * 原始查询 + 缺失关键词 + 相关同义扩展，扩大对缺失面的语义召回。
     */
    public List<String> buildRefinedQueries(String query, List<String> missingFacets) {
        if (query == null || query.isBlank() || missingFacets == null || missingFacets.isEmpty()) {
            return List.of();
        }

        Set<String> parts = new LinkedHashSet<>();
        parts.add(query);
        parts.addAll(missingFacets);

        for (String missing : missingFacets) {
            for (Facet facet : decompose(query)) {
                if (facet.keyword().equals(missing)) {
                    parts.addAll(facet.expansions());
                }
            }
        }

        List<String> refined = new ArrayList<>(parts);
        if (refined.size() == 1) {
            return List.of();
        }
        return List.of(String.join(" ", refined));
    }
}
