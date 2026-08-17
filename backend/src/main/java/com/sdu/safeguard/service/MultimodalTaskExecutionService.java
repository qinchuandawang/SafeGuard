package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdu.safeguard.dto.MultimodalReviewRequest;
import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalTaskExecutionService {

    private final AsyncTaskMapper asyncTaskMapper;
    private final DetectionTaskManager taskManager;
    private final ObjectStorageService objectStorageService;
    private final MultimodalMediaBundleService bundleService;
    private final LangGraphOrchestratorClient orchestratorClient;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public void execute(AsyncTask task) {
        ResolvedBundle resolved = resolveBundle(task);
        MultimodalMediaBundleService.ExtractedBundle extracted = null;
        boolean waitingReview = false;
        try {
            taskManager.updateProgress(task.getTaskId(), 10, Map.of("message", "正在解析多模态任务包..."));
            extracted = bundleService.extract(resolved.path());
            taskManager.updateProgress(task.getTaskId(), 20, Map.of("message", "正在并行执行文本、音频和视频检测..."));
            var metadata = extracted.metadata();
            LangGraphOrchestratorClient.WorkflowResult workflow = orchestratorClient.detectMultimodal(
                    task.getTaskId(), extracted.audioPath(), extracted.videoPath(),
                    metadata.text(),
                    metadata.audioModelId(), metadata.videoModelId());
            Map<String, Object> result = buildCanonicalResult(workflow, metadata.text(), false);
            if (workflow.waitingReview()) {
                waitingReview = true;
                taskManager.waitForReview(task.getTaskId(), result);
                return;
            }
            if (!workflow.completed()) {
                throw new IllegalStateException("LangGraph 工作流未正常完成: " + workflow.status());
            }
            taskManager.updateProgress(task.getTaskId(), 92, Map.of("message", "正在生成多模态解释报告..."));
            attachReport(result, metadata.text());
            taskManager.complete(task.getTaskId(), result);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("多模态任务执行失败", exception);
        } finally {
            bundleService.cleanup(extracted);
            resolved.cleanup(waitingReview);
        }
    }

    public Map<String, Object> review(String taskId, MultimodalReviewRequest request) {
        AsyncTask task = asyncTaskMapper.findByTaskId(taskId);
        if (task == null || !"multimodal".equals(task.getType())) {
            throw new IllegalArgumentException("多模态任务不存在");
        }
        if ("completed".equals(task.getStatus()) && task.getResultJson() != null) {
            try {
                return objectMapper.readValue(task.getResultJson(), Map.class);
            } catch (Exception exception) {
                throw new IllegalStateException("已完成任务结果损坏", exception);
            }
        }
        if (!"waiting_review".equals(task.getStatus())) {
            throw new IllegalStateException("任务当前不处于待审核状态");
        }
        ResolvedBundle resolved = resolveBundle(task);
        MultimodalMediaBundleService.ExtractedBundle extracted = null;
        try {
            extracted = bundleService.extract(resolved.path());
            LangGraphOrchestratorClient.WorkflowResult workflow = orchestratorClient.resume(
                    taskId, request.getDecision(), request.getReviewer(), request.getComment());
            if (!workflow.completed()) {
                throw new IllegalStateException("人工审核后工作流未完成: " + workflow.status());
            }
            Map<String, Object> result = buildCanonicalResult(workflow, extracted.metadata().text(), true);
            attachReport(result, extracted.metadata().text());
            try {
                taskManager.completeReview(taskId, result);
                return result;
            } catch (IllegalStateException exception) {
                AsyncTask completed = asyncTaskMapper.findByTaskId(taskId);
                if (completed != null && "completed".equals(completed.getStatus())
                        && completed.getResultJson() != null) {
                    return objectMapper.readValue(completed.getResultJson(), Map.class);
                }
                throw exception;
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("人工审核恢复失败", exception);
        } finally {
            bundleService.cleanup(extracted);
            resolved.cleanup(false);
        }
    }

    private Map<String, Object> buildCanonicalResult(LangGraphOrchestratorClient.WorkflowResult response,
                                                      String text, boolean reviewed) {
        Map<String, Object> workflow = response.workflow();
        double probability = number(workflow.get("fused_probability"), 0.5);
        double confidence = number(workflow.get("confidence"), 0.0);
        String decision = String.valueOf(workflow.getOrDefault("decision", "uncertain"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "multimodal");
        result.put("source", "langgraph-detection-workflow");
        result.put("text", text == null ? "" : text);
        result.put("result", decision);
        result.put("finalDecision", decision);
        result.put("decisionSource", reviewed ? "human_review" : "langgraph_model_workflow");
        result.put("modelRiskProbability", probability);
        result.put("riskProbability", probability);
        result.put("confidence", confidence);
        result.put("probabilities", Map.of("fake", probability, "real", 1.0 - probability));
        result.put("textResult", response.data().get("text_result"));
        result.put("audioResult", response.data().get("audio_result"));
        result.put("videoResult", response.data().get("video_result"));
        result.put("workflow", workflow);
        result.put("reviewed", reviewed);
        return result;
    }

    private void attachReport(Map<String, Object> result, String text) {
        try {
            result.put("report", llmService.analyzeMultiModalEvidence(
                    text, result.get("textResult"), result.get("audioResult"),
                    result.get("videoResult"), result.get("workflow")));
        } catch (Exception exception) {
            log.warn("多模态 AI 报告生成失败，使用确定性降级报告: {}", exception.getMessage());
            result.put("report", "模型工作流已完成，请以结构化风险概率和审核结果为准。");
        }
    }

    private ResolvedBundle resolveBundle(AsyncTask task) {
        if (task.getFilePath() != null && !task.getFilePath().isBlank()) {
            Path local = Path.of(task.getFilePath());
            if (Files.isRegularFile(local)) {
                boolean cleanup = task.getObjectKey() != null && !task.getObjectKey().isBlank();
                return new ResolvedBundle(local, cleanup);
            }
        }
        return new ResolvedBundle(objectStorageService.downloadToTemp(task.getObjectKey()), true);
    }

    private double number(Object value, double fallback) {
        if (!(value instanceof Number number)) return fallback;
        double parsed = number.doubleValue();
        if (!Double.isFinite(parsed)) return fallback;
        return Math.max(0.0, Math.min(1.0, parsed));
    }

    private record ResolvedBundle(Path path, boolean temporaryDownload) {
        private void cleanup(boolean preserveForReview) {
            if (preserveForReview && !temporaryDownload) return;
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }
}
