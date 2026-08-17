package com.sdu.safeguard.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LangGraphOrchestratorClient {

    private final RestTemplate restTemplate;

    @Value("${ai.orchestrator.multimodal-url:http://localhost:5003/v1/workflows/multimodal}")
    private String multimodalUrl;

    @Value("${ai.orchestrator.base-url:http://localhost:5003}")
    private String baseUrl;

    @Value("${ai.orchestrator.internal-token:}")
    private String internalToken;

    public LangGraphOrchestratorClient(@Qualifier("videoRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("unchecked")
    public WorkflowResult detectMultimodal(String taskId, Path audio, Path video, String text,
                                           String audioModelId, String videoModelId) {
        if (!Files.isRegularFile(audio) || !Files.isRegularFile(video)) {
            throw new IllegalArgumentException("多模态音视频文件不存在");
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("audio", new FileSystemResource(audio));
        body.add("video", new FileSystemResource(video));
        body.add("task_id", taskId);
        body.add("text", safe(text));
        body.add("audio_model_id", safe(audioModelId));
        body.add("video_model_id", safe(videoModelId));
        HttpHeaders headers = internalHeaders(taskId);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> response = restTemplate.exchange(
                multimodalUrl, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return parse(response.getBody());
    }

    @SuppressWarnings("unchecked")
    public WorkflowResult resume(String taskId, String decision, String reviewer, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("reviewer", safe(reviewer));
        body.put("comment", safe(comment));
        HttpHeaders headers = internalHeaders(taskId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = baseUrl.replaceAll("/+$", "") + "/v1/workflows/" + taskId + "/resume";
        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return parse(response.getBody());
    }

    @SuppressWarnings("unchecked")
    private WorkflowResult parse(Map<String, Object> payload) {
        if (payload == null) throw new IllegalStateException("LangGraph 返回空响应");
        Object code = payload.get("code");
        if (code instanceof Number number && number.intValue() != 0) {
            throw new IllegalStateException("LangGraph 执行失败: " + payload.get("message"));
        }
        Object dataValue = payload.get("data");
        if (!(dataValue instanceof Map<?, ?> dataMap)) {
            throw new IllegalStateException("LangGraph 未返回有效 data");
        }
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) dataMap);
        Object workflowValue = data.get("workflow");
        if (!(workflowValue instanceof Map<?, ?> workflowMap)) {
            throw new IllegalStateException("LangGraph 未返回工作流元数据");
        }
        Map<String, Object> workflow = new LinkedHashMap<>((Map<String, Object>) workflowMap);
        String status = String.valueOf(workflow.getOrDefault("status", "failed"));
        return new WorkflowResult(status, data, workflow);
    }

    private HttpHeaders internalHeaders(String taskId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Task-Id", taskId);
        headers.set("X-Trace-Id", taskId);
        if (internalToken != null && !internalToken.isBlank()) {
            headers.setBearerAuth(internalToken);
        }
        return headers;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record WorkflowResult(String status, Map<String, Object> data, Map<String, Object> workflow) {
        public boolean waitingReview() {
            return "waiting_review".equals(status);
        }

        public boolean completed() {
            return "completed".equals(status);
        }
    }
}
