package com.sdu.safeguard.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 工具调用请求 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionRequest {
    private String toolName;
    private String input;
    private String filePath;
    private String fileUrl;
    private Map<String, Object> parameters;
}