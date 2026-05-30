package com.sdu.safeguard.agent.tool;

import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.service.DetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioDetectionTool implements Tool {

    private final DetectionService detectionService;

    @Override
    public String getName() {
        return "DETECT_AUDIO";
    }

    @Override
    public String getDescription() {
        return "检测音频文件是否存在伪造/语音克隆。当用户提供音频文件并询问是否涉及AI换声或声音伪造时使用。输入应为音频文件路径，返回检测结果包括伪造概率、真实概率、置信度和风险等级。";
    }

    @Override
    public ToolResult execute(ToolExecutionRequest request) {
        try {
            String filePath = request.getFilePath();
            if (filePath != null) {
                // 文件路径模式
                File file = new File(filePath);
                if (!file.exists()) {
                    return ToolResult.fail("音频文件不存在: " + filePath);
                }
                AudioDetectionResult result = detectionService.detectAudio(filePath);
                return formatResult(result);
            }

            String fileUrl = request.getFileUrl();
            if (fileUrl != null) {
                // URL 模式（从 URL 下载后检测, 暂未实现）
                return ToolResult.fail("URL模式暂未支持，请提供文件路径");
            }

            // 从参数中获取 MultipartFile
            Object fileObj = request.getParameters() != null ? request.getParameters().get("file") : null;
            if (fileObj instanceof MultipartFile mpf) {
                AudioDetectionResult result = detectionService.detectAudioWithMultipart(mpf);
                return formatResult(result);
            }

            return ToolResult.fail("请提供音频文件路径或文件");
        } catch (Exception e) {
            log.error("音频检测失败: {}", e.getMessage());
            return ToolResult.fail("音频检测失败: " + e.getMessage());
        }
    }

    private ToolResult formatResult(AudioDetectionResult result) {
        if (result == null) {
            return ToolResult.fail("音频检测无返回结果");
        }
        String output = String.format(
                "音频检测完成:\n- 标签: %s\n- 伪造概率: %.2f%%\n- 真实概率: %.2f%%\n- 置信度: %.2f%%\n- 风险等级: %s",
                result.getLabel(),
                (result.getSpoofProb() != null ? result.getSpoofProb() : 0) * 100,
                (result.getBonafideProb() != null ? result.getBonafideProb() : 0) * 100,
                (result.getConfidence() != null ? result.getConfidence() : 0) * 100,
                result.getRiskLevel() != null ? result.getRiskLevel() : "未知"
        );
        return ToolResult.ok(output, Map.of(
                "label", result.getLabel(),
                "spoofProb", result.getSpoofProb(),
                "bonafideProb", result.getBonafideProb(),
                "confidence", result.getConfidence(),
                "riskLevel", result.getRiskLevel()
        ));
    }
}