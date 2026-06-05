package com.sdu.safeguard.controller;

import com.sdu.safeguard.agent.AgentOrchestrator;
import com.sdu.safeguard.dto.OrchestratorRequest;
import com.sdu.safeguard.dto.OrchestratorResponse;
import com.sdu.safeguard.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/safe_guard/";

    private final AgentOrchestrator orchestrator;

    @PostMapping("/analyze")
    public Result<OrchestratorResponse> analyze(@RequestBody OrchestratorRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return Result.error("查询内容不能为空");
        }
        try {
            OrchestratorResponse response = orchestrator.execute(request);
            return Result.success(response);
        } catch (Exception e) {
            log.error("Agent分析异常", e);
            return Result.error("分析失败：" + e.getMessage());
        }
    }

    @PostMapping("/analyze/text")
    public Result<OrchestratorResponse> analyzeText(@RequestBody Map<String, Object> request) {
        String text = request != null ? (String) request.get("text") : null;
        if (text == null || text.isBlank()) {
            return Result.error("文本不能为空");
        }
        String sessionId = String.valueOf(request.getOrDefault("sessionId", UUID.randomUUID().toString()));

        OrchestratorRequest agentRequest = OrchestratorRequest.builder()
                .query(text)
                .sessionId(sessionId)
                .mode("ANALYSIS")
                .requiredAgents(List.of("TEXT_ANALYSIS", "KNOWLEDGE"))
                .useReAct(true)
                .useCoT(true)
                .useRAG(true)
                .build();

        try {
            OrchestratorResponse response = orchestrator.execute(agentRequest);
            return Result.success(response);
        } catch (Exception e) {
            log.error("文本分析异常", e);
            return Result.error("分析失败：" + e.getMessage());
        }
    }

    @PostMapping("/simulate")
    public Result<OrchestratorResponse> simulate(@RequestBody Map<String, Object> request) {
        String text = request != null ? (String) request.get("text") : null;
        if (text == null || text.isBlank()) {
            return Result.error("输入不能为空");
        }
        String sessionId = String.valueOf(request.getOrDefault("sessionId", UUID.randomUUID().toString()));

        OrchestratorRequest agentRequest = OrchestratorRequest.builder()
                .query(text)
                .sessionId(sessionId)
                .mode("SIMULATION")
                .requiredAgents(List.of("SIMULATION", "KNOWLEDGE"))
                .useReAct(true)
                .useCoT(false)
                .useRAG(true)
                .build();

        try {
            OrchestratorResponse response = orchestrator.execute(agentRequest);
            return Result.success(response);
        } catch (Exception e) {
            log.error("模拟分析异常", e);
            return Result.error("分析失败：" + e.getMessage());
        }
    }

    /**
     * 多模态检测入口：同时接受文本 + 音频/视频文件。
     * 文件保存到临时目录，路径通过 AgentRequest.context 传递给 Agent。
     */
    @PostMapping("/detect")
    public Result<OrchestratorResponse> detectMultiModal(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "text", required = false) String text) {

        if (file.isEmpty()) {
            return Result.error("文件不能为空");
        }
        if (!"audio".equalsIgnoreCase(type) && !"video".equalsIgnoreCase(type)) {
            return Result.error("type 必须为 audio 或 video");
        }

        try {
            File tempDir = new File(TEMP_DIR);
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                return Result.error("无法创建临时目录");
            }

            String originalName = file.getOriginalFilename();
            String extension = "";
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf('.'));
            }
            String fileId = UUID.randomUUID().toString();
            File destFile = new File(tempDir, fileId + extension);
            file.transferTo(destFile);
            String filePath = destFile.getAbsolutePath();
            log.info("多模态检测文件已保存: {} (type={})", filePath, type);

            String effectiveText = (text != null && !text.isBlank()) ? text : "请检测此" + ("audio".equalsIgnoreCase(type) ? "音频" : "视频") + "文件";
            String sessionId = UUID.randomUUID().toString();

            String agentType = "audio".equalsIgnoreCase(type) ? "AUDIO_DETECTION" : "VIDEO_DETECTION";

            OrchestratorRequest agentRequest = OrchestratorRequest.builder()
                    .query(effectiveText)
                    .sessionId(sessionId)
                    .mode("MULTI_MODAL")
                    .requiredAgents(List.of(agentType, "KNOWLEDGE"))
                    .useReAct(true)
                    .useCoT(true)
                    .useRAG(true)
                    .filePath(filePath)
                    .fileType(type.toLowerCase())
                    .build();

            OrchestratorResponse response = orchestrator.execute(agentRequest);
            return Result.success(response);

        } catch (IOException e) {
            log.error("文件处理失败", e);
            return Result.error("文件处理失败: " + e.getMessage());
        }
    }
}
