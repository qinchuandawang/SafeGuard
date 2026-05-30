package com.sdu.safeguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {
    private String agentType;
    private String input;
    private String sessionId;
    private String userId;
    private Map<String, Object> context;
    private Map<String, Object> parameters;
    /** 文件路径（音频/视频检测使用） */
    private String filePath;
    /** 文件类型：audio / video */
    private String fileType;
}
