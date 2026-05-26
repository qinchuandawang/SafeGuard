package com.sdu.safeguard.dto;

import lombok.Data;

@Data
public class FileUploadResponse {
    private String fileId;
    private String originalName;
    private String fileType;
    private Long size;
}
