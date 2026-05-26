package com.sdu.safeguard.dto;

import lombok.Data;

@Data
public class MultiModalRequest {
    private String text;
    private AudioDetectionResult audioResult;
    private VideoDetectionResult videoResult;
}
