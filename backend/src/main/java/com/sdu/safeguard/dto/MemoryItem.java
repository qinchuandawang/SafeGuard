package com.sdu.safeguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryItem {
    private String id;
    private String sessionId;
    private String role;
    private String content;
    private long timestamp;
    private String type;
    private double importance;
    private List<String> tags;
    private String summary;
}
