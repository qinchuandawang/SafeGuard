package com.sdu.safeguard.rag;

import com.sdu.safeguard.config.RAGConfig;
import com.sdu.safeguard.dto.RagQueryResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {

    private final HybridChunker hybridChunker;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;
    private final RAGConfig ragConfig;

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
    }

    @PostConstruct
    public void init() {
        log.info("RAG服务初始化");
        loadKnowledgeDocuments();
    }

    public void loadKnowledgeDocuments() {
        try {
            // 检查集合是否已有数据，避免每次重启重复插入
            if (qdrantService.getCollectionSize() > 0) {
                log.info("知识文档已存在，跳过加载（共 {} 条）", qdrantService.getCollectionSize());
                return;
            }

            String document = loadDocumentFromResources("knowledge/anti_fraud_knowledge.txt");
            if (document == null || document.isBlank()) {
                log.warn("反诈知识文档为空或未找到");
                return;
            }

            Map<String, String> sections = splitByMajorSection(document);

            List<String> allChunkIds = new ArrayList<>();
            List<List<Float>> allVectors = new ArrayList<>();
            List<Map<String, Object>> allMetadatas = new ArrayList<>();

            for (Map.Entry<String, String> section : sections.entrySet()) {
                String title = section.getKey();
                String content = section.getValue();

                String category = extractCategory(title);
                List<String> tags = extractTags(title, content);

                List<ChunkResult> chunks = hybridChunker.hybridChunk(
                        content, "anti_fraud_knowledge.txt", category, tags);

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
            }

            qdrantService.batchInsert(allChunkIds, allVectors, allMetadatas);
            log.info("知识文档加载完成: {} 条切块已存入Qdrant", allChunkIds.size());

        } catch (Exception e) {
            log.error("加载知识文档失败", e);
        }
    }

    public List<RagQueryResult> query(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        long startTime = System.currentTimeMillis();

        List<String> queryChunks = hybridChunker.chunkQuery(queryText);
        log.debug("查询切块: {} 块", queryChunks.size());

        List<RagQueryResult> allResults = new ArrayList<>();

        for (String chunk : queryChunks) {
            List<Float> queryVector = embeddingService.getEmbedding(chunk);

            Set<String> matchedKeywords = matchKeywords(chunk);

            List<QdrantService.ScoredResult> vectorResults = qdrantService.search(queryVector,
                    ragConfig.getHnswTopK());

            for (QdrantService.ScoredResult vr : vectorResults) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = vr.metadata;
                String content = (String) meta.getOrDefault("content", "");

                double keywordScore = computeKeywordScore(content, matchedKeywords);

                double reRankScore = vr.score * 0.6 + keywordScore * 0.4;

                allResults.add(RagQueryResult.builder()
                        .chunkId(vr.chunkId)
                        .content(content)
                        .category((String) meta.getOrDefault("category", ""))
                        .source((String) meta.getOrDefault("source", ""))
                        .score(reRankScore)
                        .vectorScore(vr.score)
                        .keywordScore(keywordScore)
                        .reRankScore(reRankScore)
                        .tags(convertTags(meta.get("tags")))
                        .metadata(meta)
                        .build());
            }
        }

        List<RagQueryResult> deduplicated = semanticDeduplicate(allResults);

        List<RagQueryResult> finalResults = deduplicated.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(ragConfig.getFinalTopK())
                .collect(Collectors.toList());

        long cost = System.currentTimeMillis() - startTime;
        log.info("RAG检索完成: candidates={}, final={}, cost={}ms",
                allResults.size(), finalResults.size(), cost);

        return finalResults;
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

    private List<RagQueryResult> semanticDeduplicate(List<RagQueryResult> results) {
        if (results == null || results.size() <= 1) return results;

        List<RagQueryResult> deduped = new ArrayList<>();
        for (RagQueryResult result : results) {
            boolean isDuplicate = false;
            int existingIdx = -1;
            for (int i = 0; i < deduped.size(); i++) {
                RagQueryResult existing = deduped.get(i);
                if (computeTextSimilarity(result.getContent(), existing.getContent()) > 0.85) {
                    if (result.getScore() > existing.getScore()) {
                        existingIdx = i;
                    }
                    isDuplicate = true;
                    break;
                }
            }
            if (existingIdx >= 0) {
                deduped.set(existingIdx, result);
            } else if (!isDuplicate) {
                deduped.add(result);
            }
        }
        return deduped;
    }

    private double computeTextSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;
        Set<String> set1 = new HashSet<>();
        for (char c : s1.toCharArray()) set1.add(String.valueOf(c));
        Set<String> set2 = new HashSet<>();
        for (char c : s2.toCharArray()) set2.add(String.valueOf(c));
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

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
        }
        return sb.toString();
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
