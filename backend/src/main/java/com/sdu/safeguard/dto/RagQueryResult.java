package com.sdu.safeguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryResult {
    private String chunkId;
    private String content;
    private String category;
    private String source;
    private double score;
    private double vectorScore;
    private double keywordScore;
    private double reRankScore;
    private List<String> tags;
    private Map<String, Object> metadata;
}
