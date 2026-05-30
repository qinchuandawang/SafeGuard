package com.sdu.safeguard.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 工具执行结果 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {
    private boolean success;
    private String output;
    private String error;
    private Map<String, Object> data;

    public static ToolResult ok(String output) {
        return ToolResult.builder().success(true).output(output).build();
    }

    public static ToolResult ok(String output, Map<String, Object> data) {
        return ToolResult.builder().success(true).output(output).data(data).build();
    }

    public static ToolResult fail(String error) {
        return ToolResult.builder().success(false).error(error).build();
    }
}