package com.sdu.safeguard.controller;

import com.sdu.safeguard.agent.AgentOrchestrator;
import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.dto.DetectionTask;
import com.sdu.safeguard.dto.OrchestratorRequest;
import com.sdu.safeguard.dto.OrchestratorResponse;
import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.memory.MemoryService;
import com.sdu.safeguard.service.DetectionService;
import com.sdu.safeguard.service.DetectionTaskManager;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.service.ActiveModelRegistry;
import com.sdu.safeguard.service.ObjectStorageService;
import com.sdu.safeguard.service.DetectionWorkQueueService;
import com.sdu.safeguard.service.TaskQueueFullException;
import com.sdu.safeguard.service.MultimodalMediaBundleService;
import com.sdu.safeguard.service.MultimodalUploadStagingService;
import com.sdu.safeguard.util.InputValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
    private final MemoryService memoryService;
    private final ActiveModelRegistry activeModelRegistry;
    private final ObjectStorageService objectStorageService;
    private final DetectionWorkQueueService workQueueService;
    private final MultimodalMediaBundleService multimodalBundleService;
    private final MultimodalUploadStagingService multimodalUploadStagingService;

    @Autowired
    public DetectionController(DetectionService detectionService, LLMService llmService,
                               DetectionTaskManager taskManager, AgentOrchestrator agentOrchestrator,
                               MemoryService memoryService,
                               ActiveModelRegistry activeModelRegistry,
                               ObjectStorageService objectStorageService,
                               DetectionWorkQueueService workQueueService,
                               MultimodalMediaBundleService multimodalBundleService,
                               MultimodalUploadStagingService multimodalUploadStagingService) {
        this.detectionService = detectionService;
        this.llmService = llmService;
        this.taskManager = taskManager;
        this.agentOrchestrator = agentOrchestrator;
        this.memoryService = memoryService;
        this.activeModelRegistry = activeModelRegistry;
        this.objectStorageService = objectStorageService;
        this.workQueueService = workQueueService;
        this.multimodalBundleService = multimodalBundleService;
        this.multimodalUploadStagingService = multimodalUploadStagingService;
    }

    /** 保留旧构造器，兼容不覆盖视频异步链路的控制器单元测试。 */
    public DetectionController(DetectionService detectionService, LLMService llmService,
                               DetectionTaskManager taskManager, AgentOrchestrator agentOrchestrator,
                               ActiveModelRegistry activeModelRegistry,
                               ObjectStorageService objectStorageService) {
        this(detectionService, llmService, taskManager, agentOrchestrator, null,
                activeModelRegistry, objectStorageService, null, null, null);
    }

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
    public Result<?> detectVideo(@RequestParam("file") MultipartFile file,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        if (file == null || file.isEmpty()) {
            return Result.badRequest("视频文件不能为空");
        }
        String validationError = validateMediaFile(file, "video");
        if (validationError != null) {
            return Result.badRequest(validationError);
        }
        String fileHash;
        String modelId = activeModelRegistry.getActiveVideoModel();
        String idempotencyKey;
        try {
            fileHash = sha256(file);
            idempotencyKey = normalizeIdempotencyKey(requestKey, "video", fileHash, modelId);
        } catch (IllegalArgumentException exception) {
            return Result.badRequest(exception.getMessage());
        } catch (IOException exception) {
            return Result.error("文件摘要计算失败");
        }
        DetectionTaskManager.TaskCreation creation;
        try {
            creation = taskManager.createTaskIdempotently("video", null, idempotencyKey, fileHash, modelId);
        } catch (RuntimeException e) {
            log.error("创建视频检测任务失败", e);
            return Result.error("任务创建失败：" + e.getMessage());
        }
        DetectionTask task = creation.task();
        if (!creation.created()) {
            if ("queued".equals(task.getStatus())) {
                // 重试同一个幂等请求时补投递此前可能发送失败的工作消息。
                enqueueInferenceOrReject(task.getTaskId());
            }
            return Result.success(taskPayload(task, true));
        }
        String objectKey = null;
        String filePath;
        try {
            objectKey = objectStorageService.upload(file, "video", fileHash);
            filePath = saveToTemp(file);
            taskManager.bindInputLocation(task.getTaskId(), objectKey, filePath);
        } catch (Exception e) {
            log.error("视频文件持久化失败", e);
            taskManager.failBeforeProcessing(task.getTaskId(), "文件持久化失败");
            objectStorageService.deleteQuietly(objectKey);
            return Result.error("文件处理失败：" + e.getMessage());
        }
        // 提交接口只负责持久化输入并投递工作消息，模型调用由 RocketMQ 消费者执行。
        enqueueInferenceOrReject(task.getTaskId());
        return Result.success(taskPayload(task, false));
    }

    /**
     * 队列满时请求并未被受理，必须将已落库的 queued 任务收敛为终态，
     * 避免无工作消息的僵尸任务长期占用排队计数。
     */
    private void enqueueInferenceOrReject(String taskId) {
        try {
            workQueueService.enqueueInference(taskId);
        } catch (TaskQueueFullException exception) {
            taskManager.failBeforeProcessing(taskId, "任务未受理：" + exception.getMessage());
            throw exception;
        }
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
        String conversationId;
        try {
            conversationId = resolveConversationId(request);
        } catch (IllegalArgumentException exception) {
            return Result.badRequest(exception.getMessage());
        }
        try {
            OrchestratorRequest agentRequest = OrchestratorRequest.builder()
                    .query(text)
                    .sessionId(conversationId)
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

    @PostMapping(value = "/multi/audio-stage", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> stageMultimodalAudio(@RequestParam("file") MultipartFile audio) {
        if (audio == null || audio.isEmpty()) return Result.badRequest("音频文件不能为空");
        String validationError = validateMediaFile(audio, "audio");
        if (validationError != null) return Result.badRequest(validationError);
        if (multimodalUploadStagingService == null) return Result.error("多模态暂存服务未配置");
        try {
            String token = multimodalUploadStagingService.stage(audio, sha256(audio));
            return Result.success(Map.of("audioToken", token,
                    "expiresInSeconds", multimodalUploadStagingService.ttlSeconds()));
        } catch (Exception exception) {
            log.error("多模态音频暂存失败", exception);
            return Result.error("音频暂存失败：" + exception.getMessage());
        }
    }

    /** 历史记录以 MySQL 为准，Redis 仅用于活跃上下文加速。 */
    @GetMapping("/text/conversations/{conversationId}/messages")
    public Result<Map<String, Object>> getTextConversationHistory(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long beforeId) {
        if (!isValidConversationId(conversationId)) {
            return Result.badRequest("conversationId 格式无效");
        }
        if (memoryService == null) {
            return Result.error("会话历史服务未配置");
        }
        MemoryService.ConversationHistoryPage page = memoryService
                .getConversationHistoryPage(conversationId, limit, beforeId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messages", page.messages());
        response.put("nextBeforeId", page.nextBeforeId());
        return Result.success(response);
    }

    private String resolveConversationId(Map<String, String> request) {
        String conversationId = request.get("conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = request.get("sessionId");
        }
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = conversationId.trim();
        if (!isValidConversationId(normalized)) {
            throw new IllegalArgumentException("conversationId 格式无效");
        }
        return normalized;
    }

    private boolean isValidConversationId(String conversationId) {
        return conversationId != null && conversationId.trim().matches("[A-Za-z0-9_-]{1,128}");
    }

    @PostMapping(value = "/multi", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<?> detectMulti(@RequestParam(value = "audio", required = false) MultipartFile audio,
                                 @RequestParam("video") MultipartFile video,
                                 @RequestParam(value = "audioToken", required = false) String audioToken,
                                 @RequestParam(value = "text", required = false) String text,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        if ((audio == null || audio.isEmpty()) && (audioToken == null || audioToken.isBlank())) {
            return Result.badRequest("必须提供音频文件或 audioToken");
        }
        if (video == null || video.isEmpty()) {
            return Result.badRequest("视频文件不能为空");
        }
        String videoError = validateMediaFile(video, "video");
        if (videoError != null) return Result.badRequest(videoError);
        if (audio != null && !audio.isEmpty()) {
            String audioError = validateMediaFile(audio, "audio");
            if (audioError != null) return Result.badRequest(audioError);
        }
        String normalizedText = text == null ? "" : text.trim();
        if (!normalizedText.isBlank()) {
            String textError = InputValidator.validateAnalysisText(normalizedText);
            if (textError != null) return Result.badRequest(textError);
        }
        if (multimodalBundleService == null || workQueueService == null) {
            return Result.error("多模态异步服务未配置");
        }
        String audioModelId = activeModelRegistry.getActiveAudioModel();
        String videoModelId = activeModelRegistry.getActiveVideoModel();
        MultimodalUploadStagingService.StagedAudioHandle stagedAudio = null;
        String fileHash;
        String idempotencyKey;
        try {
            String audioHash;
            if (audio != null && !audio.isEmpty()) {
                audioHash = sha256(audio);
            } else {
                stagedAudio = multimodalUploadStagingService.consume(audioToken);
                audioHash = stagedAudio.staged().fileHash();
            }
            fileHash = combinedMultimodalHash(audioHash, sha256(video), normalizedText);
            idempotencyKey = normalizeIdempotencyKey(
                    requestKey, "multimodal", fileHash, audioModelId + ":" + videoModelId);
        } catch (IllegalArgumentException exception) {
            multimodalUploadStagingService.cleanup(stagedAudio);
            return Result.badRequest(exception.getMessage());
        } catch (IOException exception) {
            multimodalUploadStagingService.cleanup(stagedAudio);
            return Result.error("多模态文件摘要计算失败");
        }
        DetectionTaskManager.TaskCreation creation;
        try {
            creation = taskManager.createTaskIdempotently(
                    "multimodal", null, idempotencyKey, fileHash, audioModelId + ":" + videoModelId);
        } catch (RuntimeException exception) {
            multimodalUploadStagingService.cleanup(stagedAudio);
            return Result.error("多模态任务创建失败：" + exception.getMessage());
        }
        DetectionTask task = creation.task();
        if (!creation.created()) {
            multimodalUploadStagingService.cleanup(stagedAudio);
            if ("queued".equals(task.getStatus())) enqueueInferenceOrReject(task.getTaskId());
            return Result.success(taskPayload(task, true));
        }
        Path bundle = null;
        String objectKey = null;
        try {
            bundle = stagedAudio == null
                    ? multimodalBundleService.create(
                    audio, video, normalizedText, audioModelId, videoModelId)
                    : multimodalBundleService.create(
                    stagedAudio.path(), stagedAudio.staged().fileName(), video,
                    normalizedText, audioModelId, videoModelId);
            multimodalUploadStagingService.cleanup(stagedAudio);
            stagedAudio = null;
            objectKey = objectStorageService.upload(
                    bundle, "multimodal", fileHash, "application/zip");
            taskManager.bindInputLocation(task.getTaskId(), objectKey, bundle.toString());
            enqueueInferenceOrReject(task.getTaskId());
            return Result.success(taskPayload(task, false));
        } catch (Exception exception) {
            log.error("多模态任务包持久化失败", exception);
            taskManager.failBeforeProcessing(task.getTaskId(), "多模态任务包持久化失败");
            multimodalUploadStagingService.cleanup(stagedAudio);
            objectStorageService.deleteQuietly(objectKey);
            if (bundle != null) {
                try {
                    Files.deleteIfExists(bundle);
                } catch (IOException ignored) {
                }
            }
            return Result.error("多模态文件处理失败：" + exception.getMessage());
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
        return taskManager.subscribe(taskId);
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

    private Map<String, Object> buildTextDetectionResult(String text, String report) {
        String value = text == null ? "" : text;
        double risk = 0.12;
        List<String> features = new ArrayList<>();
        String scamType = "正常通知";

        if (containsAny(value, "刷单", "返利", "垫付", "做任务", "佣金")) {
            risk = Math.max(risk, 0.94);
            scamType = "刷单返利诈骗";
            features.add("刷单返利");
        }
        if (containsAny(value, "客服", "退款", "取消会员", "自动续费", "验证码", "屏幕共享")) {
            risk = Math.max(risk, 0.92);
            scamType = "冒充电商客服诈骗";
            features.add("冒充电商客服");
        }
        if (containsAny(value, "公安", "民警", "洗钱", "安全账户", "配合调查")) {
            risk = Math.max(risk, 0.95);
            scamType = "冒充公检法诈骗";
            features.add("冒充公检法");
        }
        if (containsAny(value, "AI换脸", "视频通话", "冒充熟人", "转账", "借钱")) {
            risk = Math.max(risk, 0.88);
            scamType = "AI换脸视频诈骗";
            features.add("AI换脸转账话术");
        }
        if (containsAny(value, "官方铁路", "铁路12306", "列车", "候补", "退票")) {
            risk = Math.min(risk, 0.20);
            scamType = "官方出行通知";
            features.add("官方出行通知");
        }
        if (containsAny(value, "课程汇报", "教学楼", "签到", "快递", "取件码")
                && !containsAny(value, "转账", "验证码", "链接", "安全账户")) {
            risk = Math.min(risk, 0.18);
            scamType = value.contains("快递") ? "正常物流通知" : "正常校园通知";
            features.add(scamType);
        }
        if (containsAny(value, "不涉及任何转账", "不会索要验证码", "不涉及转账")) {
            risk = Math.min(risk, 0.18);
            scamType = value.contains("课程") || value.contains("教学楼") ? "正常校园通知" : scamType;
            features.add("明确声明不涉及转账或验证码");
        }
        if (features.isEmpty()) {
            features.add(risk >= 0.7 ? "高风险诈骗话术" : "未发现典型诈骗要素");
        }

        String result = risk >= 0.75 ? "dangerous" : (risk >= 0.4 ? "suspicious" : "safe");
        double safeProbability = Math.max(0.0, 1.0 - risk);
        String effectiveReport = report == null || report.isBlank()
                ? buildRuleTextReport(result, scamType, features)
                : report;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "text");
        payload.put("result", result);
        payload.put("scamType", scamType);
        payload.put("riskProbability", risk);
        payload.put("safeProbability", safeProbability);
        payload.put("confidence", Math.max(0.65, Math.min(0.96, 0.62 + risk * 0.32)));
        payload.put("features", features);
        payload.put("suspiciousPoints", features);
        payload.put("report", effectiveReport);
        payload.put("probabilities", Map.of(
                "fake", risk,
                "real", safeProbability
        ));
        return payload;
    }

    private String buildRuleTextReport(String result, String scamType, List<String> features) {
        if ("safe".equals(result)) {
            return "该内容更像" + scamType + "，未发现明显诈骗风险。判断依据：" + String.join("、", features) + "。";
        }
        return "该内容存在明显诈骗风险，疑似" + scamType + "。主要依据：" + String.join("、", features)
                + "。建议停止转账、不要提供验证码，并通过官方渠道核实。";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String sha256(MultipartFile file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (java.io.InputStream inputStream = file.getInputStream()) {
                int length;
                while ((length = inputStream.read(buffer)) >= 0) {
                    if (length > 0) digest.update(buffer, 0, length);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private String normalizeIdempotencyKey(String requestKey, String type, String fileHash, String modelId) {
        if (requestKey != null && !requestKey.isBlank()) {
            String normalized = requestKey.trim();
            if (normalized.length() > 128) throw new IllegalArgumentException("Idempotency-Key 不能超过128字符");
            return normalized;
        }
        return type + ":" + fileHash + ":" + modelId;
    }

    private String combinedMultimodalHash(String audioHash, String videoHash, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(audioHash.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(videoHash.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private Map<String, Object> taskPayload(DetectionTask task, boolean reused) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("status", task.getStatus());
        payload.put("reused", reused);
        return payload;
    }
}
