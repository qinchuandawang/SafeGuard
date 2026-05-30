package com.sdu.safeguard.rag;

import com.sdu.safeguard.config.RAGConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class HybridChunker {

    private final RAGConfig ragConfig;

    private static final List<String> SECTION_PATTERNS = List.of(
            "## ", "### ", "【", "---"
    );

    public List<ChunkResult> hybridChunk(String document, String source, String category, List<String> tags) {
        List<ChunkResult> fixedChunks = fixedWindowChunk(document, source, category, tags);
        List<ChunkResult> sectionChunks = sectionBasedChunk(document, source, category, tags);

        Map<String, ChunkResult> merged = new LinkedHashMap<>();
        for (ChunkResult chunk : fixedChunks) {
            merged.put(chunk.getChunkId(), chunk);
        }
        for (ChunkResult chunk : sectionChunks) {
            String key = chunk.getChunkId();
            if (!merged.containsKey(key)) {
                merged.put(key, chunk);
            }
        }

        List<ChunkResult> result = new ArrayList<>(merged.values());
        log.debug("混合切块完成: fixed={}, section={}, merged={}",
                fixedChunks.size(), sectionChunks.size(), result.size());
        return result;
    }

    public List<ChunkResult> fixedWindowChunk(String document, String source, String category, List<String> tags) {
        List<ChunkResult> chunks = new ArrayList<>();
        int chunkSize = ragConfig.getFixedChunkSize();
        int overlap = ragConfig.getFixedChunkOverlap();
        int step = chunkSize - overlap;

        if (document == null || document.isBlank()) {
            return chunks;
        }

        int pos = 0;
        int index = 0;
        while (pos < document.length()) {
            int end = Math.min(pos + chunkSize, document.length());
            if (end < document.length()) {
                int sentenceEnd = findSentenceBoundary(document, end, chunkSize / 2);
                if (sentenceEnd > pos) {
                    end = sentenceEnd;
                }
            }

            String content = document.substring(pos, end).trim();
            if (!content.isBlank()) {
                chunks.add(ChunkResult.builder()
                        .chunkId("fixed_" + source + "_" + index)
                        .content(content)
                        .category(category)
                        .source(source)
                        .tags(tags)
                        .startPos(pos)
                        .endPos(end)
                        .chunkType("FIXED")
                        .build());
                index++;
            }
            pos += step;
            if (step <= 0) break;
        }

        return chunks;
    }

    /**
     * 按章节标题（##、###、【）切分的段落级分块，非语义聚类。
     */
    public List<ChunkResult> sectionBasedChunk(String document, String source, String category, List<String> tags) {
        List<ChunkResult> chunks = new ArrayList<>();
        if (document == null || document.isBlank()) return chunks;

        List<String> paragraphs = splitBySection(document);
        int globalIndex = 0;

        for (String para : paragraphs) {
            if (para.isBlank()) continue;

            int maxSize = ragConfig.getSemanticChunkMaxSize();
            int minSize = ragConfig.getSemanticChunkMinSize();

            if (para.length() <= maxSize) {
                chunks.add(ChunkResult.builder()
                        .chunkId("section_" + source + "_" + globalIndex)
                        .content(para.trim())
                        .category(category)
                        .source(source)
                        .tags(tags)
                        .startPos(0)
                        .endPos(para.length())
                        .chunkType("SECTION")
                        .build());
                globalIndex++;
            } else {
                int pos = 0;
                int step = maxSize - minSize;
                while (pos < para.length()) {
                    int end = Math.min(pos + maxSize, para.length());
                    if (end < para.length()) {
                        int sentenceEnd = findSentenceBoundary(para, end, minSize);
                        if (sentenceEnd > pos) {
                            end = sentenceEnd;
                        }
                    }
                    String content = para.substring(pos, end).trim();
                    if (!content.isBlank()) {
                        chunks.add(ChunkResult.builder()
                                .chunkId("section_" + source + "_" + globalIndex)
                                .content(content)
                                .category(category)
                                .source(source)
                                .tags(tags)
                                .startPos(pos)
                                .endPos(end)
                                .chunkType("SECTION")
                                .build());
                        globalIndex++;
                    }
                    pos += step;
                    if (step <= 0) break;
                }
            }
        }

        return chunks;
    }

    private List<String> splitBySection(String document) {
        List<String> sections = new ArrayList<>();
        String[] lines = document.split("\n");
        StringBuilder currentSection = new StringBuilder();

        for (String line : lines) {
            boolean isSectionHeader = SECTION_PATTERNS.stream().anyMatch(line::startsWith);
            if (isSectionHeader && currentSection.length() > 0) {
                sections.add(currentSection.toString().trim());
                currentSection = new StringBuilder();
            }
            currentSection.append(line).append("\n");
        }
        if (currentSection.length() > 0) {
            sections.add(currentSection.toString().trim());
        }

        return sections;
    }

    private int findSentenceBoundary(String text, int startPos, int maxLookahead) {
        int end = Math.min(startPos + maxLookahead, text.length());
        for (int i = startPos; i < end; i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '；') {
                return i + 1;
            }
            if ((c == '：' || c == ':') && i > startPos + 10) {
                return i + 1;
            }
        }
        return startPos;
    }

    public List<String> chunkQuery(String queryText) {
        if (queryText == null || queryText.isBlank()) return List.of();
        if (queryText.length() <= ragConfig.getFixedChunkSize()) {
            return List.of(queryText);
        }
        List<String> chunks = fixedWindowChunk(queryText, "query", "", List.of())
                .stream().map(ChunkResult::getContent)
                .collect(Collectors.toList());
        return chunks.isEmpty() ? List.of(queryText) : chunks;
    }
}
