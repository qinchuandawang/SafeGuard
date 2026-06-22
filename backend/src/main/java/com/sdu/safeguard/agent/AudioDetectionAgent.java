package com.sdu.safeguard.agent;

import com.sdu.safeguard.agent.tool.AudioDetectionTool;
import com.sdu.safeguard.agent.tool.ToolExecutionRequest;
import com.sdu.safeguard.agent.tool.ToolResult;
import com.sdu.safeguard.dto.AgentRequest;
import com.sdu.safeguard.dto.AgentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioDetectionAgent implements Agent {

    private final AudioDetectionTool audioTool;

    @Override
    public String getType() {
        return "AUDIO_DETECTION";
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        String filePath = resolveFilePath(request);
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .toolName("DETECT_AUDIO")
                .input(request.getInput())
                .filePath(filePath)
                .parameters(request.getParameters())
                .build();

        ToolResult tr = audioTool.execute(toolReq);

        if (!tr.isSuccess()) {
            return AgentResponse.builder()
                    .agentType("AUDIO_DETECTION")
                    .status("FAILED")
                    .error(tr.getError() != null ? tr.getError() : "音频检测失败")
                    .build();
        }

        return AgentResponse.builder()
                .agentType("AUDIO_DETECTION")
                .result(tr.getOutput())
                .confidence(extractDouble(tr.getData(), "confidence"))
                .data(tr.getData())
                .actions(List.of("DEEPSEEK_TOOL_ROUTING", "AUDIO_DETECTION", "FLASK_INFERENCE", "LLM_REPORT"))
                .reasoning("DeepSeek 作为中枢 Agent 调度 Wav2Vec2 音频训练模型完成推理，并将模型结果交给大模型综合研判")
                .status("SUCCESS")
                .build();
    }

    private String resolveFilePath(AgentRequest request) {
        if (request.getFilePath() != null) return request.getFilePath();
        if (request.getContext() != null) {
            Object ctxPath = request.getContext().get("filePath");
            if (ctxPath instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private double extractDouble(Map<String, Object> data, String key) {
        if (data == null) return 0.0;
        Object v = data.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
