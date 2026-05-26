package com.sdu.safeguard.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkResult {
    private String chunkId;
    private String content;
    private String category;
    private String source;
    private List<String> tags;
    private int startPos;
    private int endPos;
    private String chunkType;
}
