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
public class ReActThought {
    private int step;
    private String thought;
    private String action;
    private String actionInput;
    private String observation;
    private String finalAnswer;
    private List<String> referencedSources;
    private boolean isFinal;
}
