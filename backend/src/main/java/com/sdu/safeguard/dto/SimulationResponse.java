package com.sdu.safeguard.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SimulationResponse {
    private String content;
    private List<String> quickReplies = new ArrayList<>();
    private Integer suspicionDelta = 0;
    private Boolean finished = false;
    private String analysis;
    private List<String> tips = new ArrayList<>();
}
