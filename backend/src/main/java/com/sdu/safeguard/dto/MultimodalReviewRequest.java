package com.sdu.safeguard.dto;

import lombok.Data;

@Data
public class MultimodalReviewRequest {
    private String decision;
    private String reviewer;
    private String comment;
}
