package com.sdu.safeguard.controller;

import com.sdu.safeguard.agent.AgentOrchestrator;
import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.dto.DetectionTask;
import com.sdu.safeguard.dto.MultiModalRequest;
import com.sdu.safeguard.dto.OrchestratorRequest;
import com.sdu.safeguard.dto.OrchestratorResponse;
import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.service.DetectionService;
import com.sdu.safeguard.service.DetectionTaskManager;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.util.InputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@RestController
@RequestMapping("/api/detection")
@RequiredArgsConstructor
public class DetectionController {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/safe_guard/";
    private static final long MAX_AUDIO_SIZE = 20L * 1024 * 1024;
    private static final long MAX_VIDEO_SIZE = 100L * 1024 * 1024;
    private static final long MAX_TEXT_DOC_SIZE = 10L * 1024 * 1024;
    private static final int MAX_EXTRACTED_TEXT_LENGTH = 5000;
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(".wav", ".flac", ".mp3", ".m4a", ".ogg");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".avi", ".mkv", ".webm");
    private static final Set<String> TEXT_DOCUMENT_EXTENSIONS = Set.of(".txt", ".pdf", ".docx");

    private final DetectionService detectionService;
    private final LLMService llmService;
    private final DetectionTaskManager taskManager;
    private final AgentOrchestrator agentOrchestrator;

    @PostMapping("/audio")
    public Result<?> detectAudio(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("音频文件不能为空");
        }
        String validationError = validateMediaFile(file, "audio");
        if (validationError != null) {
            return Result.badRequest(validationError);
        }
        try {
            // 直接上传 MultipartFile 到检测服务，避免跨容器文件路径问题
            AudioDetectionResult result = detectionService.detectAudioWithMultipart(file);
            // 调用 LLM 生成详细分析报告（Key 未配置时返回规则化降级报告，非空）
            if (result != null) {
                try {
                    result.setReport(llmService.generateAudioReport(result));
                } catch (Exception e) {
                    log.warn("生成音频分析报告失败，使用降级报告: {}", e.getMessage());
                    result.setReport("AI 报告生成失败，请查看上方模型检测数据。");
                }
            }
            return Result.success(buildAudioAgentPayload(result));
        } catch (Exception e) {
            log.error("音频检测失败: {}", e.getMessage(), e);
            return Result.error("音频检测失败：" + e.getMessage());
        }
    }

    @PostMapping("/audio/batch")
    public Result<List<Map<String, Object>>> detectAudioBatch(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return Result.badRequest("请至少上传一个音频文件");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String validationError = validateMediaFile(file, "audio");
            if (validationError != null) {
                return Result.badRequest("文件 " + file.getOriginalFilename() + " 校验失败: " + validationError);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", file.getOriginalFilename());
            try {
                AudioDetectionResult result = detectionService.detectAudioWithMultipart(file);
                if (result != null) {
                    try {
                        result.setReport(llmService.generateAudioReport(result));
                    } catch (Exception e) {
                        log.warn("批量音频报告生成失败，使用降级报告: {}", e.getMessage());
                        result.setReport("AI 报告生成失败，请查看模型检测结果。");
                    }
                }
                item.put("success", true);
                item.put("result", buildAudioAgentPayload(result));
            } catch (Exception e) {
                log.error("批量音频检测失败: {}", file.getOriginalFilename(), e);
                item.put("success", false);
                item.put("error", e.getMessage());
            }
            results.add(item);
        }
        return Result.success(results);
    }

    @PostMapping("/video")
    public Result<?> detectVideo(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.badRequest("视频文件不能为空");
        }
        String validationError = validateMediaFile(file, "video");
        if (validationError != null) {
            return Result.badRequest(validationError);
        }
        String filePath;
        try {
            filePath = saveToTemp(file);
        } catch (IOException e) {
            log.error("视频文件保存失败", e);
            return Result.error("文件处理失败");
        }
        DetectionTask task;
        try {
            task = taskManager.createTask("video");
        } catch (RuntimeException e) {
            // DB 故障等：createTask 抛异常时 filePath 已保存，lambda 还没启动，必须在外层清理
            log.error("创建视频检测任务失败，清理临时文件", e);
            deleteQuietly(filePath);
            return Result.error("任务创建失败：" + e.getMessage());
        }
        taskManager.runAsync(task.getTaskId(), taskId -> {
            try {
                log.info("视频检测任务进度: taskId={}, 文件已保存，开始检测 path={}", taskId, filePath);
                taskManager.updateProgress(taskId, 0, Map.of("message", "准备处理视频文件..."));
                VideoDetectionResult result = detectionService.detectVideo(filePath,
                        (pct, detail) -> {
                            log.info("视频检测任务进度: taskId={}, progress={}%, detail={}", taskId, pct, detail);
                            taskManager.updateProgress(taskId, pct, detail);
                        });
                // 调用 LLM 生成详细分析报告（Key 未配置时返回规则化降级报告，非空）
                if (result != null) {
                    try {
                        log.info("视频检测任务进度: taskId={}, 模型结果完成，开始生成 AI 报告 fakeProbability={}, determination={}, fakeType={}",
                                taskId, result.getFakeProbability(), result.getDetermination(), result.getFakeType());
                        taskManager.updateProgress(taskId, 90, Map.of("message", "正在生成 AI 分析报告..."));
                        result.setReport(llmService.generateVideoReport(result));
                    } catch (Exception e) {
                        log.warn("生成视频分析报告失败，使用降级报告: {}", e.getMessage());
                        result.setReport("AI 报告生成失败，请查看上方模型检测数据。");
                    }
                }
                log.info("视频检测任务完成: taskId={}, finalDetermination={}, fakeProbability={}, confidence={}",
                        taskId,
                        result == null ? null : result.getDetermination(),
                        result == null ? null : result.getFakeProbability(),
                        result == null ? null : result.getConfidence());
                taskManager.complete(taskId, buildVideoAgentPayload(result));
            } finally {
                deleteQuietly(filePath);
            }
        });
        return Result.success(Map.of("taskId", task.getTaskId(), "status", "processing"));
    }

    @PostMapping("/text")
    public Result<?> detectText(@RequestBody Map<String, String> request) {
        if (request == null) {
            return Result.error("请求体不能为空");
        }
        String text = request.get("text");
        String validationError = InputValidator.validateAnalysisText(text);
        if (validationError != null) {
            return Result.error(validationError);
        }
        try {
            OrchestratorRequest agentRequest = OrchestratorRequest.builder()
                    .query(text)
                    .sessionId(UUID.randomUUID().toString())
                    .mode("TEXT_DETECTION")
                    .requiredAgents(List.of("TEXT_ANALYSIS", "KNOWLEDGE"))
                    .useReAct(true)
                    .useCoT(true)
                    .useRAG(true)
                    .build();
            OrchestratorResponse response = agentOrchestrator.execute(agentRequest);
            return Result.success(buildTextAgentPayload(response));
        } catch (Exception e) {
            log.error("文本检测异常: type={}, msg={}", e.getClass().getSimpleName(), e.getMessage(), e);
            return Result.error("检测失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @PostMapping("/multi")
    public Result<Map<String, Object>> detectMulti(@RequestBody MultiModalRequest request) {
        if (request == null) {
            return Result.error("请求体不能为空");
        }
        try {
            String report = llmService.analyzeMultiModal(
                    request.getText(),
                    request.getAudioResult(),
                    request.getVideoResult()
            );
            return Result.success(buildMultiModalPayload(request, report));
        } catch (Exception e) {
            log.error("综合检测异常: type={}, msg={}", e.getClass().getSimpleName(), e.getMessage(), e);
            return Result.error("综合检测失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @PostMapping(value = "/text/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<?> detectTextDocument(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.badRequest("文档不能为空");
        }
        String extension = extractExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (file.getSize() > MAX_TEXT_DOC_SIZE) {
            return Result.badRequest("文档不能超过10MB");
        }
        if (!TEXT_DOCUMENT_EXTENSIONS.contains(extension)) {
            return Result.badRequest("仅支持 txt、pdf、docx 文档");
        }
        try {
            String extractedText = extractDocumentText(file, extension);
            String normalizedText = normalizeDocumentText(extractedText);
            String validationError = InputValidator.validateAnalysisText(normalizedText);
            if (validationError != null) {
                return Result.error(validationError);
            }
            OrchestratorRequest agentRequest = OrchestratorRequest.builder()
                    .query(normalizedText)
                    .sessionId(UUID.randomUUID().toString())
                    .mode("TEXT_DOCUMENT_DETECTION")
                    .requiredAgents(List.of("TEXT_ANALYSIS", "KNOWLEDGE"))
                    .useReAct(true)
                    .useCoT(true)
                    .useRAG(true)
                    .build();
            OrchestratorResponse response = agentOrchestrator.execute(agentRequest);
            Map<String, Object> payload = buildTextAgentPayload(response);
            payload.put("documentName", file.getOriginalFilename());
            payload.put("documentType", extension.replace(".", ""));
            payload.put("extractedText", normalizedText);
            payload.put("source", "deepseek-agent-orchestrator-document");
            return Result.success(payload);
        } catch (Exception e) {
            log.error("文档文本检测异常: type={}, msg={}", e.getClass().getSimpleName(), e.getMessage(), e);
            return Result.error("文档检测失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @GetMapping("/task/{taskId}")
    public Result<Map<String, Object>> getTask(@PathVariable String taskId) {
        DetectionTask task = taskManager.getTask(taskId);
        if (task == null) {
            return Result.error("任务不存在");
        }
        Map<String, Object> resultMap = new java.util.LinkedHashMap<>();
        resultMap.put("taskId", task.getTaskId());
        resultMap.put("status", task.getStatus());
        resultMap.put("progress", task.getProgress());
        resultMap.put("result", task.getResult());
        resultMap.put("error", task.getError());
        return Result.success(resultMap);
    }

    @GetMapping(value = "/task/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@PathVariable String taskId) {
        DetectionTask task = taskManager.getTask(taskId);
        if (task == null || task.getSseEmitter() == null) {
            SseEmitter gone = new SseEmitter(0L);
            gone.complete();
            return gone;
        }
        return task.getSseEmitter();
    }

    private String saveToTemp(MultipartFile file) throws IOException {
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + tempDir.getAbsolutePath());
        }
        String originalName = file.getOriginalFilename();
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf('.'));
        }
        String fileName = UUID.randomUUID() + extension;
        File destFile = new File(tempDir, fileName);
        file.transferTo(destFile);
        return destFile.getAbsolutePath();
    }

    private String validateMediaFile(MultipartFile file, String type) {
        String extension = extractExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if ("audio".equals(type)) {
            if (file.getSize() > MAX_AUDIO_SIZE) {
                return "音频文件不能超过20MB";
            }
            if (!AUDIO_EXTENSIONS.contains(extension)) {
                return "不支持的音频格式";
            }
        }
        if ("video".equals(type)) {
            if (file.getSize() > MAX_VIDEO_SIZE) {
                return "视频文件不能超过100MB";
            }
            if (!VIDEO_EXTENSIONS.contains(extension)) {
                return "不支持的视频格式";
            }
        }
        return null;
    }

    private String extractExtension(String originalName) {
        if (originalName == null || !originalName.contains(".")) {
            return "";
        }
        return originalName.substring(originalName.lastIndexOf('.'));
    }

    private String extractDocumentText(MultipartFile file, String extension) throws IOException {
        if (".txt".equals(extension)) {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        }
        if (".pdf".equals(extension)) {
            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(document);
            }
        }
        if (".docx".equals(extension)) {
            return extractDocxText(file);
        }
        throw new IOException("不支持的文档格式");
    }

    private String extractDocxText(MultipartFile file) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    return xml.replaceAll("<w:tab\\s*/>", "\t")
                            .replaceAll("</w:p>", "\n")
                            .replaceAll("<[^>]+>", "")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'");
                }
            }
        }
        return "";
    }

    private String normalizeDocumentText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (normalized.length() > MAX_EXTRACTED_TEXT_LENGTH) {
            return normalized.substring(0, MAX_EXTRACTED_TEXT_LENGTH);
        }
        return normalized;
    }

    private static void deleteQuietly(String filePath) {
        if (filePath == null) return;
        File f = new File(filePath);
        if (f.exists() && !f.delete()) {
            log.debug("临时文件删除失败（可能被占用）: {}", filePath);
        }
    }

    private Map<String, Object> buildTextAgentPayload(OrchestratorResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "text");
        payload.put("source", "deepseek-agent-orchestrator");
        payload.put("orchestrator", response);
        if (response == null || response.getAgentResults() == null) {
            payload.put("report", "大模型编排未返回结果，请稍后重试。");
            return payload;
        }
        var textAgent = response.getAgentResults().get("TEXT_ANALYSIS");
        if (textAgent != null && textAgent.getData() != null) {
            payload.putAll(textAgent.getData());
        }
        if (!payload.containsKey("riskLevel") && response.getCotResult() != null) {
            var cot = response.getCotResult();
            payload.put("scamType", cot.getScamType());
            payload.put("riskLevel", cot.getRiskLevel());
            payload.put("riskProbability", cot.getRiskProbability());
            payload.put("confidence", cot.getRiskProbability());
            payload.put("probabilities", Map.of(
                    "fake", clampProbability(cot.getRiskProbability()),
                    "real", Math.max(0.0, 1.0 - clampProbability(cot.getRiskProbability()))
            ));
            payload.put("suspiciousPoints", cot.getSuspiciousPoints());
            payload.put("features", cot.getSuspiciousPoints());
            if (cot.getAdvice() != null && !cot.getAdvice().isBlank()) {
                payload.put("advice", List.of(cot.getAdvice()));
            }
            payload.put("reasoningSteps", cot.getReasoningSteps());
        }
        payload.putIfAbsent("report", response.getFinalResult());
        payload.put("report", normalizeTextReportForDisplay(payload, response));
        payload.putIfAbsent("agentSteps", buildAgentSteps(response));
        payload.put("reasoningSummary", response.getReasoningSummary());
        payload.put("processingTimeMs", response.getProcessingTimeMs());
        if (response.getRagContext() != null && !response.getRagContext().isEmpty()) {
            payload.put("knowledgeSources", response.getRagContext());
        }
        return payload;
    }

    private String normalizeTextReportForDisplay(Map<String, Object> payload, OrchestratorResponse response) {
        Object reportObj = payload.get("report");
        String report = reportObj instanceof String s ? s.trim() : "";
        if (!report.isBlank() && !looksStructuredReport(report)) {
            return report;
        }
        String riskLevel = String.valueOf(payload.getOrDefault("riskLevel", "medium"));
        String riskText = switch (riskLevel.toLowerCase(Locale.ROOT)) {
            case "high" -> "高风险";
            case "low" -> "低风险";
            default -> "中等风险";
        };
        String scamType = String.valueOf(payload.getOrDefault("scamType", "未知诈骗类型"));
        StringBuilder sb = new StringBuilder();
        sb.append("本次文本检测判断为").append(riskText).append("，疑似类型为").append(scamType).append("。");
        Object points = payload.get("suspiciousPoints");
        if (points instanceof List<?> list && !list.isEmpty()) {
            sb.append("主要可疑点包括：");
            sb.append(String.join("；", list.stream().filter(Objects::nonNull).map(Object::toString).toList()));
            sb.append("。");
        } else if (response != null && response.getCotResult() != null
                && response.getCotResult().getReasoningSteps() != null
                && !response.getCotResult().getReasoningSteps().isEmpty()) {
            sb.append("判断依据包括：");
            sb.append(String.join("；", response.getCotResult().getReasoningSteps()));
            sb.append("。");
        }
        Object advice = payload.get("advice");
        if (advice instanceof List<?> list && !list.isEmpty()) {
            sb.append("建议：");
            sb.append(String.join("；", list.stream().filter(Objects::nonNull).map(Object::toString).toList()));
            sb.append("。");
        } else {
            sb.append("建议暂停转账、付款或提供验证码，通过官方渠道核实对方身份和消息来源。");
        }
        return sb.toString();
    }

    private boolean looksStructuredReport(String report) {
        String value = report == null ? "" : report.trim();
        return value.startsWith("{")
                || value.startsWith("##")
                || value.contains("\"riskLevel\"")
                || value.contains("### TEXT_ANALYSIS")
                || value.contains("检测详情:");
    }

    private Map<String, Object> buildAudioAgentPayload(AudioDetectionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "audio");
        payload.put("source", "deepseek-agent-orchestrator");
        payload.put("model", "Wav2Vec2");
        payload.put("result", result);
        if (result == null) {
            payload.put("report", "音频检测未返回结果。");
            payload.put("agentSteps", buildMediaAgentSteps("音频", "Wav2Vec2", "检测服务未返回有效结果"));
            return payload;
        }
        payload.put("label", result.getLabel());
        payload.put("spoofProb", result.getSpoofProb());
        payload.put("fakeProbability", result.getFakeProbability());
        payload.put("confidence", result.getConfidence());
        payload.put("riskLevel", result.getRiskLevel());
        payload.put("probabilities", result.getProbabilities());
        payload.put("fileName", result.getFileName());
        payload.put("fileSizeBytes", result.getFileSizeBytes());
        payload.put("modelVersion", result.getModelVersion());
        payload.put("device", result.getDevice());
        payload.put("latencyMs", result.getLatencyMs());
        payload.put("sampleRate", result.getSampleRate());
        payload.put("originalSampleRate", result.getOriginalSampleRate());
        payload.put("channels", result.getChannels());
        payload.put("durationSeconds", result.getDurationSeconds());
        payload.put("analyzedSeconds", result.getAnalyzedSeconds());
        payload.put("truncated", result.getTruncated());
        payload.put("report", result.getReport());
        payload.put("agentSteps", buildMediaAgentSteps("音频", "Wav2Vec2", "DeepSeek 已综合音频模型输出生成用户报告"));
        payload.put("orchestratorSteps", List.of(
                "DeepSeek 中枢接收音频检测任务",
                "调度 Wav2Vec2 音频伪造检测模型完成推理",
                "结合模型概率、置信度和反诈场景生成解释报告"
        ));
        return payload;
    }

    private Map<String, Object> buildVideoAgentPayload(VideoDetectionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "video");
        payload.put("source", "deepseek-agent-orchestrator");
        payload.put("model", "XceptionNet");
        payload.put("result", result);
        if (result == null) {
            payload.put("report", "视频检测未返回结果。");
            payload.put("agentSteps", buildMediaAgentSteps("视频", "XceptionNet", "检测服务未返回有效结果"));
            return payload;
        }
        payload.put("fakeProbability", result.getFakeProbability());
        payload.put("visualFakeProbability", result.getVisualFakeProbability());
        payload.put("averageFakeProbability", result.getAverageFakeProbability());
        payload.put("maxFakeProbability", result.getMaxFakeProbability());
        payload.put("suspiciousFrameRatio", result.getSuspiciousFrameRatio());
        payload.put("confidence", result.getConfidence());
        payload.put("determination", result.getDetermination());
        payload.put("fakeType", result.getFakeType());
        payload.put("totalFrames", result.getTotalFrames());
        payload.put("totalFaces", result.getTotalFaces());
        payload.put("framesWithFace", result.getFramesWithFace());
        payload.put("aigcMetadataDetected", result.getAigcMetadataDetected());
        payload.put("metadataEvidence", result.getMetadataEvidence());
        payload.put("evidenceReasons", result.getEvidenceReasons());
        payload.put("probabilities", result.getProbabilities());
        payload.put("frameAnalysis", result.getFrameAnalysis());
        payload.put("report", result.getReport());
        payload.put("agentSteps", buildMediaAgentSteps("视频", "XceptionNet", "DeepSeek 已综合视频模型输出生成用户报告"));
        payload.put("orchestratorSteps", List.of(
                "DeepSeek 中枢接收视频检测任务",
                "调度 XceptionNet 视频换脸检测模型完成推理",
                "结合逐帧人脸证据、伪造概率和反诈场景生成解释报告"
        ));
        return payload;
    }

    private List<Map<String, Object>> buildMediaAgentSteps(String mediaName, String modelName, String finalDescription) {
        return List.of(
                Map.of("name", "DeepSeek 中枢调度", "status", "completed", "description", "识别" + mediaName + "检测任务并选择专用模型工具"),
                Map.of("name", modelName + " 模型推理", "status", "completed", "description", "调用训练模型输出伪造概率和置信度"),
                Map.of("name", "大模型综合研判", "status", "completed", "description", finalDescription)
        );
    }

    private Map<String, Object> buildMultiModalPayload(MultiModalRequest request, String report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "multi");
        payload.put("source", "deepseek-agent-orchestrator");
        payload.put("report", report);
        payload.put("text", request.getText());
        payload.put("audioResult", request.getAudioResult());
        payload.put("videoResult", request.getVideoResult());

        double audioRisk = extractAudioRisk(request.getAudioResult());
        double videoRisk = extractVideoRisk(request.getVideoResult());
        double textHintRisk = request.getText() != null && !request.getText().isBlank() ? 0.35 : 0.0;
        double finalRisk = Math.max(Math.max(audioRisk, videoRisk), textHintRisk);
        payload.put("confidence", Math.max(0.65, Math.min(0.95, 0.65 + finalRisk * 0.25)));
        payload.put("riskProbability", finalRisk);
        payload.put("probabilities", Map.of(
                "fake", finalRisk,
                "real", Math.max(0.0, 1.0 - finalRisk)
        ));
        payload.put("agentSteps", List.of(
                Map.of("name", "DeepSeek 中枢接收任务", "status", "completed", "description", "汇总文本、音频和视频输入"),
                Map.of("name", "音频模型调度", "status", request.getAudioResult() == null ? "skipped" : "completed", "description", "调用 Wav2Vec2 输出语音伪造证据"),
                Map.of("name", "视频模型调度", "status", request.getVideoResult() == null ? "skipped" : "completed", "description", "调用 XceptionNet 输出换脸检测证据"),
                Map.of("name", "多模态综合研判", "status", "completed", "description", "DeepSeek 融合模型结果和反诈知识生成最终报告")
        ));
        return payload;
    }

    private double extractAudioRisk(AudioDetectionResult result) {
        if (result == null) return 0.0;
        Double value = result.getSpoofProb() != null ? result.getSpoofProb() : result.getFakeProbability();
        return clampProbability(value);
    }

    private double extractVideoRisk(VideoDetectionResult result) {
        if (result == null) return 0.0;
        return clampProbability(result.getFakeProbability());
    }

    private double clampProbability(Double value) {
        if (value == null || value.isNaN()) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private List<Map<String, Object>> buildAgentSteps(OrchestratorResponse response) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (response.getReactThoughts() != null) {
            response.getReactThoughts().forEach(thought -> steps.add(Map.of(
                    "name", "ReAct Step " + thought.getStep(),
                    "status", "completed",
                    "description", thought.getThought() == null ? "" : thought.getThought(),
                    "action", thought.getAction() == null ? "" : thought.getAction()
            )));
        }
        if (steps.isEmpty() && response.getReasoningSummary() != null) {
            response.getReasoningSummary().forEach(summary -> steps.add(Map.of(
                    "name", "Agent 分析",
                    "status", "completed",
                    "description", summary
            )));
        }
        return steps;
    }
}
