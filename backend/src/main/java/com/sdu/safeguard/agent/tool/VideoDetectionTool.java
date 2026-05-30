package com.sdu.safeguard.agent.tool;

import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.service.DetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoDetectionTool implements Tool {

    private final DetectionService detectionService;

    @Override
    public String getName() {
        return "DETECT_VIDEO";
    }

    @Override
    public String getDescription() {
        return "检测视频文件是否存在AI换脸/深度伪造。当用户提供视频文件并询问是否涉及DeepFake或换脸时使用。输入应为视频文件路径，返回检测结果包括伪造概率、置信度等。";
    }

    @Override
    public ToolResult execute(ToolExecutionRequest request) {
        try {
            String filePath = request.getFilePath();
            if (filePath == null || filePath.isBlank()) {
                return ToolResult.fail("请提供视频文件路径");
            }
            File file = new File(filePath);
            if (!file.exists()) {
                return ToolResult.fail("视频文件不存在: " + filePath);
            }

            VideoDetectionResult result = detectionService.detectVideo(filePath);
            if (result == null) {
                return ToolResult.fail("视频检测无返回结果");
            }

            String output = String.format(
                    "视频检测完成:\n- 伪造概率: %.2f%%\n- 真实概率: %.2f%%\n- 置信度: %.2f%%\n- 类型: %s",
                    (result.getFakeProbability() != null ? result.getFakeProbability() : 0) * 100,
                    (result.getProbabilities() != null && result.getProbabilities().getReal() != null
                            ? result.getProbabilities().getReal() : 0) * 100,
                    (result.getConfidence() != null ? result.getConfidence() : 0) * 100,
                    result.getFakeType() != null ? result.getFakeType() : "未知"
            );
            return ToolResult.ok(output, Map.of(
                    "fakeProbability", result.getFakeProbability(),
                    "confidence", result.getConfidence(),
                    "fakeType", result.getFakeType()
            ));
        } catch (Exception e) {
            log.error("视频检测失败: {}", e.getMessage());
            return ToolResult.fail("视频检测失败: " + e.getMessage());
        }
    }
}