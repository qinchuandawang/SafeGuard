package com.sdu.safeguard.rag.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.RAGConfig;
import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.rag.RAGService;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.util.PromptLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agentic RAG 编排服务 — 在单轮混合检索（{@link RAGService}）之上实现
 * "分析 → 检索 → 反思 → 计划 → 定向补检 → 聚合" 的循环。
 *
 * 能力：
 *  - 查询分解：识别查询中的多个知识面（多意图问题）；
 *  - 充分性自检：规则覆盖度判定（零 LLM 成本），可选 LLM 反思补充缺失面；
 *  - 定向补检：针对缺失知识面构造补检查询并合并重排；
 *  - 执行痕迹：完整记录 analyze/retrieve/reflect 步骤，供测评与审计。
 *
 * {@link #query(String)} 保持与 {@link RAGService#query} 一致的签名，
 * 便于现有调用方（Orchestrator / KnowledgeAgent / RAGTool）无缝切换。
 */
@Slf4j
@Service
public class AgenticRAGService {

    private final RAGService ragService;
    private final QueryDecomposer queryDecomposer;
    private final SufficiencyJudge sufficiencyJudge;
    private final RAGConfig ragConfig;
    private final ObjectProvider<LLMService> llmServiceProvider;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final Cache<String, AgenticRagResult> agenticResultCache;

    public AgenticRAGService(RAGService ragService,
                             QueryDecomposer queryDecomposer,
                             SufficiencyJudge sufficiencyJudge,
                             RAGConfig ragConfig,
                             ObjectProvider<LLMService> llmServiceProvider,
                             ObjectMapper objectMapper,
                             PromptLoader promptLoader,
                             @Qualifier("agenticResultCache") Cache<String, AgenticRagResult> agenticResultCache) {
        this.ragService = ragService;
        this.queryDecomposer = queryDecomposer;
        this.sufficiencyJudge = sufficiencyJudge;
        this.ragConfig = ragConfig;
        this.llmServiceProvider = llmServiceProvider;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.agenticResultCache = agenticResultCache;
    }

    /** 兼容入口：返回与单轮 RAG 相同的 Top-K 列表。 */
    public List<RagQueryResult> query(String queryText) {
        return agenticQuery(queryText).getResults();
    }

    /** 完整 Agentic RAG 执行，返回结果 + 执行痕迹。 */
    public AgenticRagResult agenticQuery(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return AgenticRagResult.builder()
                    .results(List.of()).query(queryText).subQueries(List.of())
                    .trace(List.of("analyze: 空查询，跳过")).iterations(0)
                    .coverageScore(0.0).sufficient(false).refined(false)
                    .costMs(0L).missingFacets(List.of()).build();
        }

        String normalized = queryText.trim();
        AgenticRagResult cached = agenticResultCache.getIfPresent(normalized);
        if (cached != null) {
            return cached;
        }

        AgenticRagResult result = ragConfig.isAgenticEnabled()
                ? runAgenticLoop(normalized)
                : runSingleShot(normalized);

        agenticResultCache.put(normalized, result);
        return result;
    }

    /** 关闭 Agentic 时的退化路径：单轮检索 + 最小痕迹。 */
    private AgenticRagResult runSingleShot(String query) {
        long start = System.currentTimeMillis();
        List<RagQueryResult> results = ragService.query(query);
        List<String> trace = List.of(
                "analyze: agentic 已禁用，退化为单轮混合检索",
                "retrieve[1]: base query -> " + results.size() + " chunks");
        return AgenticRagResult.builder()
                .results(results).query(query).subQueries(List.of(query))
                .trace(trace).iterations(1)
                .coverageScore(1.0).sufficient(true).refined(false)
                .costMs(System.currentTimeMillis() - start).missingFacets(List.of())
                .build();
    }

    private AgenticRagResult runAgenticLoop(String query) {
        long start = System.currentTimeMillis();
        List<String> trace = new ArrayList<>();
        List<String> subQueries = new ArrayList<>();
        List<String> missingFacets = new ArrayList<>();

        // 1. 分析/计划：拆解知识面
        List<QueryDecomposer.Facet> facets = queryDecomposer.decompose(query);
        trace.add("analyze: facets=" + facets.stream().map(QueryDecomposer.Facet::keyword).toList());

        // 2. 首轮检索
        List<RagQueryResult> results = new ArrayList<>(ragService.query(query));
        subQueries.add(query);
        trace.add("retrieve[1]: base query -> " + results.size() + " chunks");

        int iterations = 1;
        boolean refined = false;
        double coverageThreshold = ragConfig.getAgenticCoverageThreshold();
        SufficiencyJudge.Verdict verdict = sufficiencyJudge.judge(query, results, coverageThreshold);
        missingFacets = new ArrayList<>(verdict.missingFacets());
        trace.add("reflect[1]: coverage=" + fmt(verdict.coverage())
                + ", sufficient=" + verdict.sufficient() + ", missing=" + missingFacets);

        // 3. 反思-计划-补检循环
        while (iterations < ragConfig.getAgenticMaxIterations()) {
            List<String> missing = new ArrayList<>(missingFacets);

            // 可选 LLM 反思：补充规则判定未覆盖的缺失面
            if (ragConfig.isAgenticUseLlmReflection()) {
                LlmReflectionOutcome outcome = llmReflect(query, results);
                if (outcome != null) {
                    trace.add("llm-reflect[" + iterations + "]: sufficient=" + outcome.sufficient()
                            + ", missing=" + outcome.missingFacets());
                    if (outcome.sufficient()) {
                        missingFacets = List.of();
                        verdict = new SufficiencyJudge.Verdict(true, verdict.coverage(), List.of(), verdict.maxScore());
                        trace.add("reflect[" + iterations + "]: LLM 判定知识充分，终止");
                        break;
                    }
                    if (!outcome.missingFacets().isEmpty()) {
                        missing = outcome.missingFacets();
                    }
                }
            }

            if (verdict.sufficient() || missing.isEmpty()) {
                break;
            }

            List<String> refinedQueries = queryDecomposer.buildRefinedQueries(query, missing);
            if (refinedQueries.isEmpty()) {
                trace.add("reflect[" + iterations + "]: 无可补检方向，终止");
                break;
            }

            List<RagQueryResult> extra = new ArrayList<>();
            for (String rq : refinedQueries) {
                extra.addAll(ragService.query(rq));
                subQueries.add(rq);
            }
            iterations++;
            refined = true;
            trace.add("retrieve[" + iterations + "]: refined=" + refinedQueries + " -> +" + extra.size() + " chunks");

            if (extra.isEmpty()) {
                trace.add("reflect[" + iterations + "]: 补检无新增知识，终止");
                break;
            }

            results = mergeRanked(results, extra);
            verdict = sufficiencyJudge.judge(query, results, coverageThreshold);
            missingFacets = new ArrayList<>(verdict.missingFacets());
            trace.add("reflect[" + iterations + "]: coverage=" + fmt(verdict.coverage())
                    + ", sufficient=" + verdict.sufficient() + ", missing=" + missingFacets);
        }

        long cost = System.currentTimeMillis() - start;
        return AgenticRagResult.builder()
                .results(results).query(query).subQueries(subQueries).trace(trace)
                .iterations(iterations).coverageScore(verdict.coverage())
                .sufficient(verdict.sufficient()).refined(refined)
                .costMs(cost).missingFacets(missingFacets)
                .build();
    }

    /** 按 chunkId 合并多轮结果，同块取高分，重排取 Top-K。 */
    private List<RagQueryResult> mergeRanked(List<RagQueryResult> base, List<RagQueryResult> extra) {
        Map<String, RagQueryResult> byChunk = new LinkedHashMap<>();
        for (RagQueryResult r : base) {
            byChunk.putIfAbsent(chunkKey(r), r);
        }
        for (RagQueryResult r : extra) {
            String key = chunkKey(r);
            RagQueryResult existing = byChunk.get(key);
            if (existing == null || r.getScore() > existing.getScore()) {
                byChunk.put(key, r);
            }
        }
        return byChunk.values().stream()
                .sorted(Comparator.comparingDouble(RagQueryResult::getScore).reversed())
                .limit(ragConfig.getFinalTopK())
                .collect(Collectors.toList());
    }

    private String chunkKey(RagQueryResult r) {
        if (r.getChunkId() != null && !r.getChunkId().isBlank()) {
            return r.getChunkId();
        }
        String source = r.getSource() == null ? "" : r.getSource();
        String content = r.getContent() == null ? "" : r.getContent();
        return source + "|" + content.hashCode();
    }

    /** 将检索结果压缩成反思用的知识摘要。 */
    private String summarizeResults(List<RagQueryResult> results) {
        if (results == null || results.isEmpty()) {
            return "（未检索到相关知识）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RagQueryResult r = results.get(i);
            sb.append(i + 1).append(".【").append(r.getCategory() == null ? "" : r.getCategory()).append("】");
            if (r.getContent() != null) {
                sb.append(r.getContent().substring(0, Math.min(80, r.getContent().length())));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 可选 LLM 反思：判定知识是否充分并列出缺失面。失败时返回 null 回退规则判定。 */
    private LlmReflectionOutcome llmReflect(String query, List<RagQueryResult> results) {
        try {
            LLMService llm = llmServiceProvider.getIfAvailable();
            if (llm == null) {
                return null;
            }
            String prompt = promptLoader.loadPrompt("rag_reflection", Map.of(
                    "query", query,
                    "knowledge", summarizeResults(results)));
            String raw = llm.complete(prompt, "rag-reflection");
            JsonNode root = objectMapper.readTree(extractJson(raw));
            boolean sufficient = root.path("sufficient").asBoolean(false);
            List<String> missing = new ArrayList<>();
            JsonNode missingNode = root.path("missingFacets");
            if (missingNode.isArray()) {
                missingNode.forEach(n -> {
                    if (n.isTextual() && !n.asText().isBlank()) {
                        missing.add(n.asText().trim());
                    }
                });
            }
            return new LlmReflectionOutcome(sufficient, missing);
        } catch (Exception e) {
            log.warn("LLM 反思失败，回退规则判定: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        String cleaned = raw.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("\\s*```$", "");
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }

    private record LlmReflectionOutcome(boolean sufficient, List<String> missingFacets) {
    }

    // ===== 透传方法（供调用方/证据层复用） =====

    public java.util.Set<String> matchKeywords(String query) {
        return ragService.matchKeywords(query);
    }

    public String formatRagContext(List<RagQueryResult> results) {
        return ragService.formatRagContext(results);
    }
}
