package com.sdu.safeguard.rag;

import com.github.benmanes.caffeine.cache.Cache;
import com.sdu.safeguard.config.RAGConfig;
import com.sdu.safeguard.dto.RagQueryResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {

    /**
     * 语义缓存只缓存检索结果，不缓存最终风险结论或报告。
     */
    public record SemanticCacheEntry(String query, List<Float> vector,
                                     List<RagQueryResult> results, String ruleSignature) {
    }

    private final HybridChunker hybridChunker;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;
    private final RAGConfig ragConfig;
    private final RuleFilter ruleFilter;
    private final QueryRewriter queryRewriter;

    /** 知识文件内容哈希，用于自动检测文件变更 */
    private volatile String knowledgeContentHash = "";

    @Qualifier("ragResultCache")
    private final Cache<String, List<RagQueryResult>> ragResultCache;

    @Qualifier("ragSemanticCache")
    private final Cache<String, SemanticCacheEntry> ragSemanticCache;

    private static final Map<String, Double> FRAUD_KEYWORDS = new LinkedHashMap<>();

    static {
        FRAUD_KEYWORDS.put("刷单", 0.9);
        FRAUD_KEYWORDS.put("兼职", 0.7);
        FRAUD_KEYWORDS.put("投资", 0.8);
        FRAUD_KEYWORDS.put("理财", 0.7);
        FRAUD_KEYWORDS.put("客服", 0.7);
        FRAUD_KEYWORDS.put("退款", 0.8);
        FRAUD_KEYWORDS.put("公检法", 0.95);
        FRAUD_KEYWORDS.put("安全账户", 0.95);
        FRAUD_KEYWORDS.put("验证码", 0.85);
        FRAUD_KEYWORDS.put("转账", 0.75);
        FRAUD_KEYWORDS.put("保证金", 0.85);
        FRAUD_KEYWORDS.put("解冻费", 0.9);
        FRAUD_KEYWORDS.put("冒充", 0.8);
        FRAUD_KEYWORDS.put("借钱", 0.7);
        FRAUD_KEYWORDS.put("熟人", 0.6);
        FRAUD_KEYWORDS.put("贷款", 0.75);
        FRAUD_KEYWORDS.put("征信", 0.65);
        FRAUD_KEYWORDS.put("航班", 0.6);
        FRAUD_KEYWORDS.put("游戏", 0.5);
        FRAUD_KEYWORDS.put("交友", 0.65);
        FRAUD_KEYWORDS.put("杀猪盘", 0.9);
        FRAUD_KEYWORDS.put("返利", 0.8);
        FRAUD_KEYWORDS.put("高回报", 0.8);
        FRAUD_KEYWORDS.put("屏幕共享", 0.9);
        FRAUD_KEYWORDS.put("96110", 0.95);
        FRAUD_KEYWORDS.put("反诈", 0.7);
        FRAUD_KEYWORDS.put("电信诈骗", 0.8);
        FRAUD_KEYWORDS.put("贷款", 0.75);
        FRAUD_KEYWORDS.put("征信", 0.75);
        FRAUD_KEYWORDS.put("退改签", 0.75);
        FRAUD_KEYWORDS.put("快递", 0.65);
        FRAUD_KEYWORDS.put("中奖", 0.75);
        FRAUD_KEYWORDS.put("会员", 0.65);
        FRAUD_KEYWORDS.put("校园贷", 0.85);
        FRAUD_KEYWORDS.put("钓鱼", 0.8);
        FRAUD_KEYWORDS.put("二维码", 0.6);
        FRAUD_KEYWORDS.put("无障碍", 0.8);
        FRAUD_KEYWORDS.put("账号", 0.65);
        FRAUD_KEYWORDS.put("授权", 0.6);
        FRAUD_KEYWORDS.put("证件", 0.7);
        FRAUD_KEYWORDS.put("合同", 0.65);
        FRAUD_KEYWORDS.put("印章", 0.65);
        FRAUD_KEYWORDS.put("证据", 0.6);
        FRAUD_KEYWORDS.put("止损", 0.8);
        FRAUD_KEYWORDS.put("报警", 0.7);
        FRAUD_KEYWORDS.put("冻结", 0.7);
        FRAUD_KEYWORDS.put("止付", 0.8);
    }

    @PostConstruct
    public void init() {
        log.info("RAG服务初始化");
        loadKnowledgeDocuments();
    }

    public void loadKnowledgeDocuments() {
        try {
            if (qdrantService.getCollectionSize() > 0) {
                log.info("知识文档已存在，跳过加载（共 {} 条）。如需重新加载请调用 /api/rag/reload", qdrantService.getCollectionSize());
                return;
            }

            String document = loadDocumentFromResources("knowledge/anti_fraud_knowledge.txt");
            if (document == null || document.isBlank()) {
                log.warn("反诈知识文档为空或未找到");
                return;
            }

            String newHash = md5Hex(document);
            if (!knowledgeContentHash.isEmpty() && knowledgeContentHash.equals(newHash)) {
                log.info("知识文档无变化 (hash={})，跳过加载", newHash);
                return;
            }
            knowledgeContentHash = newHash;

            Map<String, String> sections = splitByMajorSection(document);

            List<String> allChunkIds = new ArrayList<>();
            List<List<Float>> allVectors = new ArrayList<>();
            List<Map<String, Object>> allMetadatas = new ArrayList<>();

            int sectionIndex = 0;
            for (Map.Entry<String, String> section : sections.entrySet()) {
                String title = section.getKey();
                String content = section.getValue();

                String category = extractCategory(title);
                List<String> tags = extractTags(title, content);
                String sectionSource = "anti_fraud_knowledge_" + sectionIndex + ".txt";

                List<ChunkResult> chunks = hybridChunker.hybridChunk(
                        content, sectionSource, category, tags);

                for (ChunkResult chunk : chunks) {
                    List<Float> vector = embeddingService.getEmbedding(chunk.getContent());

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("chunkId", chunk.getChunkId());
                    metadata.put("content", chunk.getContent());
                    metadata.put("category", chunk.getCategory());
                    metadata.put("source", chunk.getSource());
                    metadata.put("tags", chunk.getTags());
                    metadata.put("sectionTitle", title);
                    metadata.put("chunkType", chunk.getChunkType());

                    allChunkIds.add(chunk.getChunkId());
                    allVectors.add(vector);
                    allMetadatas.add(metadata);
                }
                sectionIndex++;
            }

            qdrantService.batchInsert(allChunkIds, allVectors, allMetadatas);
            log.info("知识文档加载完成: hash={}, {} 条切块已存入Qdrant", newHash, allChunkIds.size());

        } catch (Exception e) {
            log.error("加载知识文档失败", e);
        }
    }

    private String md5Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * 完整的 RAG 查询流程:
     * 1. 规则过滤 (RuleFilter) — 匹配已知欺诈模式
     * 2. 查询改写 (QueryRewriter) — 扩展短查询
     * 3. 混合切块查询
     * 4. 向量检索
     * 5. 关键词打分
     * 6. Cross-encoder rerank
     * 7. 多因子重排序
     * 8. Embedding 去重
     * 9. Top-5 返回
     */
    public List<RagQueryResult> query(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        String normalizedQuery = queryText.toLowerCase().trim();
        String cacheKey = "rag:" + normalizedQuery;
        List<RagQueryResult> cached = ragResultCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("RAG 缓存命中: query=\"{}\"", queryText);
            return cached;
        }

        long startTime = System.currentTimeMillis();

        // 1. 规则过滤
        RuleFilter.RuleResult ruleResult = ruleFilter.match(queryText);

        // 2. 查询改写
        QueryRewriter.RewriteResult rewriteResult = queryRewriter.rewrite(queryText);
        String searchQuery = rewriteResult.getRewritten();
        log.debug("查询改写: \"{}\" → \"{}\"", queryText, searchQuery);

        // 3. 检测嵌入服务可用性
        boolean embeddingAvailable = embeddingService.isAvailable();
        if (!embeddingAvailable) {
            log.warn("嵌入服务不可用，仅执行关键词检索");
        }

        List<Float> semanticQueryVector = null;
        if (embeddingAvailable) {
            semanticQueryVector = embeddingService.getEmbedding(searchQuery);
            SemanticCacheEntry semanticCached = findSemanticCached(
                    semanticQueryVector, ruleSignature(ruleResult));
            if (semanticCached != null) {
                log.debug("RAG 语义缓存命中: query={}, cachedQuery={}",
                        queryText, semanticCached.query());
                ragResultCache.put(cacheKey, semanticCached.results());
                return semanticCached.results();
            }
        }

        List<String> queryChunks = hybridChunker.chunkQuery(searchQuery);
        log.debug("查询切块: {} 块, embeddingAvailable={}", queryChunks.size(), embeddingAvailable);

        List<RagQueryResult> allResults = new ArrayList<>();
        Set<String> matchedKeywords = matchKeywords(queryText);

        for (String chunk : queryChunks) {
            List<QdrantService.ScoredResult> vectorResults;
            if (embeddingAvailable) {
                List<Float> queryVector = chunk.equals(searchQuery)
                        ? semanticQueryVector
                        : embeddingService.getEmbedding(chunk);
                // 规则命中只作为排序特征和风险证据，避免不完整标签导致召回被硬过滤为 0。
                vectorResults = qdrantService.search(queryVector, ragConfig.getHnswTopK());
            } else {
                List<Float> neighborVector = embeddingService.getEmbedding(chunk);
                vectorResults = qdrantService.search(neighborVector, ragConfig.getHnswTopK());
            }

            for (QdrantService.ScoredResult vr : vectorResults) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = vr.metadata;
                String content = (String) meta.getOrDefault("content", "");
                double keywordScore = computeKeywordScore(content, matchedKeywords);

                allResults.add(RagQueryResult.builder()
                        .chunkId(vr.chunkId)
                        .content(content)
                        .category((String) meta.getOrDefault("category", ""))
                        .source((String) meta.getOrDefault("source", ""))
                        .score(embeddingAvailable ? vr.score : keywordScore)
                        .vectorScore(embeddingAvailable ? vr.score : 0.0)
                        .keywordScore(keywordScore)
                        .reRankScore(vr.score)
                        .tags(convertTags(meta.get("tags")))
                        .metadata(meta)
                        .build());
            }
        }

        // 关键词检索补充向量候选之外的知识块，避免相关内容因未进入向量 Top-K 而永久丢失。
        if (!matchedKeywords.isEmpty()) {
            for (Map<String, Object> metadata : qdrantService.getAllMetadata()) {
                String content = String.valueOf(metadata.getOrDefault("content", ""));
                double keywordScore = computeKeywordScore(content, matchedKeywords);
                if (keywordScore <= 0) continue;

                String chunkId = String.valueOf(metadata.getOrDefault("chunkId", UUID.randomUUID()));
                boolean exists = allResults.stream().anyMatch(result ->
                        chunkId.equals(String.valueOf(result.getMetadata().getOrDefault("chunkId", ""))));
                if (exists) continue;

                allResults.add(RagQueryResult.builder()
                        .chunkId(chunkId)
                        .content(content)
                        .category((String) metadata.getOrDefault("category", ""))
                        .source((String) metadata.getOrDefault("source", ""))
                        .score(keywordScore)
                        .vectorScore(0.0)
                        .keywordScore(keywordScore)
                        .reRankScore(-1.0)
                        .tags(convertTags(metadata.get("tags")))
                        .metadata(metadata)
                        .build());
            }
        }

        // 4. Cross-encoder rerank（仅在嵌入和 rerank 均可用时）
        if (embeddingAvailable) {
            allResults = applyRerank(searchQuery, allResults);
        }

        // 5. 多因子重排序
        for (RagQueryResult r : allResults) {
            boolean hasRerank = r.getReRankScore() >= 0;
            double combinedScore;
            if (embeddingAvailable && hasRerank) {
                combinedScore = r.getReRankScore() * 0.5 + r.getVectorScore() * 0.3 + r.getKeywordScore() * 0.2;
            } else if (embeddingAvailable) {
                combinedScore = r.getVectorScore() * 0.6 + r.getKeywordScore() * 0.4;
            } else {
                combinedScore = r.getKeywordScore();
            }
            r.setScore(combinedScore);
        }

        // 6. Embedding 去重
        List<RagQueryResult> deduplicated = embeddingDeduplicate(allResults);

        // 7. 排序取 Top-K
        List<RagQueryResult> finalResults = deduplicated.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(ragConfig.getFinalTopK())
                .collect(Collectors.toList());

        // 8. 注入规则过滤结果
        if (ruleResult.isMatched()) {
            for (RagQueryResult r : finalResults) {
                Map<String, Object> enrichedMeta = new HashMap<>(
                        r.getMetadata() != null ? r.getMetadata() : new HashMap<>());
                enrichedMeta.put("ruleFilter", Map.of(
                        "matched", true,
                        "riskScore", ruleResult.getRiskScore(),
                        "matchedKeywords", ruleResult.getMatchedKeywords(),
                        "categories", ruleResult.getCategories()
                ));
                r.setMetadata(enrichedMeta);
            }
        }

        long cost = System.currentTimeMillis() - startTime;
        log.info("RAG检索完成: candidates={}, final={}, embedding={}, rules={}, cost={}ms",
                allResults.size(), finalResults.size(),
                embeddingAvailable ? "可用" : "不可用", ruleResult.isMatched(), cost);

        ragResultCache.put(cacheKey, finalResults);
        if (semanticQueryVector != null) {
            ragSemanticCache.put(
                    UUID.randomUUID().toString(),
                    new SemanticCacheEntry(
                            normalizedQuery,
                            semanticQueryVector,
                            finalResults,
                            ruleSignature(ruleResult)));
        }
        return finalResults;
    }

    private SemanticCacheEntry findSemanticCached(List<Float> queryVector, String ruleSignature) {
        if (queryVector == null || queryVector.isEmpty()) {
            return null;
        }
        double threshold = ragConfig.getSemanticSimilarityThreshold();
        return ragSemanticCache.asMap().values().stream()
                .filter(entry -> Objects.equals(entry.ruleSignature(), ruleSignature))
                .map(entry -> Map.entry(entry, embeddingService.cosineSimilarity(
                        queryVector, entry.vector())))
                .filter(item -> item.getValue() >= threshold)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String ruleSignature(RuleFilter.RuleResult result) {
        if (result == null) {
            return "none";
        }
        return result.isMatched() + "|" + result.getRiskScore() + "|"
                + result.getCategories();
    }

    /**
     * Cross-encoder rerank：从 candidate 中取前 rerankTopK 条，
     * 调用 rerank API，将 rerank score 写回。
     */
    private List<RagQueryResult> applyRerank(String query, List<RagQueryResult> candidates) {
        if (candidates == null || candidates.isEmpty()) return candidates;

        int rerankK = Math.min(ragConfig.getRerankTopK(), candidates.size());

        List<RagQueryResult> topCandidates = candidates.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(rerankK)
                .collect(Collectors.toList());

        List<String> documents = topCandidates.stream()
                .map(RagQueryResult::getContent)
                .collect(Collectors.toList());

        List<Double> rerankScores = embeddingService.rerank(query, documents);

        boolean rerankAvailable = false;
        for (int i = 0; i < topCandidates.size(); i++) {
            if (i < rerankScores.size() && rerankScores.get(i) >= 0) {
                topCandidates.get(i).setReRankScore(rerankScores.get(i));
                if (rerankScores.get(i) >= 0) rerankAvailable = true;
            }
        }

        if (!rerankAvailable) {
            log.debug("Rerank 不可用，保持原有评分");
        }

        // 未参与 rerank 的候选维持原分
        return candidates;
    }

    /**
     * 基于 embedding 余弦相似度的去重，使用一次性批量获取优化 API 调用。
     */
    private List<RagQueryResult> embeddingDeduplicate(List<RagQueryResult> results) {
        if (results == null || results.size() <= 1) return results;
        if (!embeddingService.isAvailable()) {
            // 嵌入不可用时不做去重（退化为简单位置去重）
            return results;
        }

        double threshold = ragConfig.getDedupThreshold();
        List<RagQueryResult> deduped = new ArrayList<>();
        List<List<Float>> dedupedVectors = new ArrayList<>();
        // 本地缓存避免重复 getEmbedding 调用
        Map<String, List<Float>> localCache = new HashMap<>();

        for (RagQueryResult result : results) {
            if (result.getContent() == null) continue;

            List<Float> vec = localCache.computeIfAbsent(result.getContent(),
                    k -> embeddingService.getEmbedding(k));
            boolean isDuplicate = false;

            for (int i = 0; i < dedupedVectors.size(); i++) {
                double sim = embeddingService.cosineSimilarity(vec, dedupedVectors.get(i));
                if (sim > threshold) {
                    isDuplicate = true;
                    boolean currentRerankOk = deduped.get(i).getReRankScore() >= 0;
                    boolean newRerankOk = result.getReRankScore() >= 0;
                    boolean better = (newRerankOk && !currentRerankOk) ||
                            (newRerankOk && currentRerankOk && result.getReRankScore() > deduped.get(i).getReRankScore()) ||
                            (!newRerankOk && !currentRerankOk && result.getScore() > deduped.get(i).getScore());
                    if (better) {
                        deduped.set(i, result);
                        dedupedVectors.set(i, vec);
                    }
                    break;
                }
            }

            if (!isDuplicate) {
                deduped.add(result);
                dedupedVectors.add(vec);
            }
        }

        return deduped;
    }

    public Set<String> matchKeywords(String text) {
        Set<String> matched = new HashSet<>();
        if (text == null || text.isBlank()) return matched;
        String lower = text.toLowerCase();
        for (String keyword : FRAUD_KEYWORDS.keySet()) {
            if (lower.contains(keyword)) {
                matched.add(keyword);
            }
        }
        return matched;
    }

    private double computeKeywordScore(String content, Set<String> queryKeywords) {
        if (queryKeywords == null || queryKeywords.isEmpty()) return 0.0;
        double score = 0.0;
        String lower = content.toLowerCase();
        for (String keyword : queryKeywords) {
            if (lower.contains(keyword)) {
                score += FRAUD_KEYWORDS.getOrDefault(keyword, 0.5);
            }
        }
        return Math.min(score / queryKeywords.size(), 1.0);
    }

    /**
     * 格式化 RAG 结果并附上规则过滤结果，用于注入 LLM Prompt。
     */
    public String formatRagContext(List<RagQueryResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【参考反诈知识库】\n");
        for (int i = 0; i < results.size(); i++) {
            RagQueryResult r = results.get(i);
            sb.append("\n--- 参考资料 ").append(i + 1).append(" ---\n");
            sb.append("分类: ").append(r.getCategory()).append("\n");
            sb.append("内容: ").append(r.getContent()).append("\n");
            sb.append("相关度: ").append(String.format("%.2f", r.getScore())).append("\n");

            // 如有规则过滤结果，附加显示
            if (r.getMetadata() != null && r.getMetadata().get("ruleFilter") instanceof Map<?, ?> rf) {
                sb.append("规则匹配: 是 (风险评分: ").append(rf.get("riskScore")).append(")\n");
            }
        }
        return sb.toString();
    }

    // ========== 以下方法保持不变 ==========

    public String formatRagContextWithRules(List<RagQueryResult> results, RuleFilter.RuleResult ruleResult) {
        String context = formatRagContext(results);
        String ruleContext = ruleFilter.formatForPrompt(ruleResult);
        if (!ruleContext.isEmpty()) {
            return ruleContext + "\n\n" + context;
        }
        return context;
    }

    private String loadDocumentFromResources(String path) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("读取知识文档失败: {}", path, e);
            return null;
        }
    }

    private Map<String, String> splitByMajorSection(String document) {
        Map<String, String> sections = new LinkedHashMap<>();
        String[] lines = document.split("\n");
        StringBuilder currentContent = new StringBuilder();
        String currentTitle = "前言";

        for (String line : lines) {
            if (line.startsWith("## ") && line.length() > 3) {
                if (currentContent.length() > 0) {
                    sections.put(currentTitle, currentContent.toString().trim());
                }
                currentTitle = line.substring(3).trim();
                currentContent = new StringBuilder();
            } else {
                currentContent.append(line).append("\n");
            }
        }
        if (currentContent.length() > 0) {
            sections.put(currentTitle, currentContent.toString().trim());
        }
        return sections;
    }

    private String extractCategory(String title) {
        if (title.contains("诈骗类型")) return "诈骗类型";
        if (title.contains("反诈利器")) return "反诈利器";
        if (title.contains("法律")) return "法律法规";
        if (title.contains("关键词")) return "防诈关键词";
        if (title.contains("热线")) return "咨询问答";
        if (title.contains("报警")) return "报警流程";
        if (title.contains("人群")) return "防范指南";
        return "综合知识";
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTags(String title, String content) {
        List<String> tags = new ArrayList<>();
        tags.add(title);
        for (Map.Entry<String, Double> entry : FRAUD_KEYWORDS.entrySet()) {
            if (content.contains(entry.getKey()) && tags.size() < 10) {
                tags.add(entry.getKey());
            }
        }
        return tags;
    }

    @SuppressWarnings("unchecked")
    private List<String> convertTags(Object tagsObj) {
        if (tagsObj instanceof List) {
            return (List<String>) tagsObj;
        }
        return List.of();
    }

    public int getTotalChunks() {
        return qdrantService.getCollectionSize();
    }

    public List<String> getTopics() {
        List<Map<String, Object>> allMeta = qdrantService.getAllMetadata();
        return allMeta.stream()
                .map(m -> (String) m.getOrDefault("category", ""))
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    public void reloadKnowledge() {
        qdrantService.dropCollection();
        ruleFilter.reload();
        knowledgeContentHash = "";
        ragResultCache.invalidateAll();
        ragSemanticCache.invalidateAll();
        loadKnowledgeDocuments();
    }

    public String queryForLLM(String queryText) {
        List<RagQueryResult> results = query(queryText);
        return formatRagContext(results);
    }

    public Set<String> getMatchedKeywords(String queryText) {
        return matchKeywords(queryText);
    }
}
