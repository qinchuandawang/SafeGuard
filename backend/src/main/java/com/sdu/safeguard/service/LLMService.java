package com.sdu.safeguard.service;

import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.dto.Message;
import com.sdu.safeguard.dto.ScamScenario;
import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.entity.KnowledgeItem;
import com.sdu.safeguard.util.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private static final String DEFAULT_TEXT = "无补充描述";
    private static final String FALLBACK_GENERAL = "AI服务暂时不可用，请稍后重试。";
    private static final String FALLBACK_RATE_LIMIT = "当前访问量较大，请稍后重试。";
    private static final String FALLBACK_TIMEOUT = "网络连接超时，请检查网络后重试。";
    private static final String FALLBACK_SERVER_ERROR = "AI服务暂时不可用，请稍后重试。";
    private static final int RAG_MAX_ITEMS = 3;

    private final RestTemplate restTemplate;
    private final LLMConfig llmConfig;
    private final PromptLoader promptLoader;
    private final KnowledgeService knowledgeService;
    private final ApplicationContext applicationContext;

    public String analyzeText(String text) {
        return callLLM(buildAnalyzePrompt(text));
    }

    public String buildAnalyzePrompt(String text) {
        String knowledge = buildKnowledgeContext(text);
        return promptLoader.loadPrompt("text_analysis", Map.of(
                "text", text,
                "knowledge", knowledge
        ));
    }

    public String analyzeMultiModal(String text, AudioDetectionResult audioResult, VideoDetectionResult videoResult) {
        String effectiveText = text != null ? text : DEFAULT_TEXT;
        String knowledge = buildKnowledgeContext(effectiveText);
        return callLLM(promptLoader.loadPrompt("multimodal_analysis", Map.of(
                "text", effectiveText,
                "knowledge", knowledge,
                "audio", audioResult != null ? audioResult : new AudioDetectionResult(),
                "video", videoResult != null ? videoResult : new VideoDetectionResult()
        )));
    }

    public String scamSimulation(String userMessage, List<Message> history, ScamScenario scenario) {
        String effectiveMessage = userMessage == null ? "" : userMessage;
        String knowledge = buildKnowledgeContext(effectiveMessage);
        return callLLM(promptLoader.loadPrompt("scam_simulation", Map.of(
                "scamScenario", scenario.getSystemPrompt(),
                "knowledge", knowledge,
                "conversationHistory", buildConversationHistory(history),
                "userMessage", effectiveMessage
        )));
    }

    private String buildConversationHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder historyBuilder = new StringBuilder();
        for (Message msg : history) {
            historyBuilder.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return historyBuilder.toString();
    }

    private String buildKnowledgeContext(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        List<KnowledgeItem> items = knowledgeService.search(keyword);
        if (items.isEmpty()) {
            return "";
        }
        List<KnowledgeItem> topItems = items.size() > RAG_MAX_ITEMS
                ? items.subList(0, RAG_MAX_ITEMS)
                : items;
        StringBuilder sb = new StringBuilder();
        sb.append("【参考反诈知识】\n");
        for (int i = 0; i < topItems.size(); i++) {
            KnowledgeItem item = topItems.get(i);
            sb.append("问题").append(i + 1).append("：").append(item.getQuestion()).append("\n");
            sb.append("答案").append(i + 1).append("：").append(item.getAnswer()).append("\n");
        }
        return sb.toString();
    }

    private String callLLM(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmConfig.getApiKey());

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(buildRequestBody(prompt), headers);

        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    llmConfig.getApiUrl(),
                    HttpMethod.POST,
                    requestEntity,
                    Object.class
            );
            return extractContent(response.getBody());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                log.error("LLM API 限流(429)，重试耗尽");
                return FALLBACK_RATE_LIMIT;
            }
            if (e.getStatusCode().is4xxClientError()) {
                log.error("LLM API 客户端错误: {} - {}", e.getStatusCode(), e.getMessage());
                return FALLBACK_GENERAL;
            }
            log.error("LLM API 错误({}): {}", e.getStatusCode(), e.getMessage());
            return FALLBACK_GENERAL;
        } catch (HttpServerErrorException e) {
            log.error("LLM API 服务端错误({})，重试耗尽", e.getStatusCode());
            return FALLBACK_SERVER_ERROR;
        } catch (ResourceAccessException e) {
            log.error("LLM API 连接超时，重试耗尽: {}", e.getMessage());
            return FALLBACK_TIMEOUT;
        } catch (Exception e) {
            log.error("LLM API 未知错误", e);
            return FALLBACK_GENERAL;
        }
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", llmConfig.getModel());
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", llmConfig.getTemperature());
        requestBody.put("max_tokens", llmConfig.getMaxTokens());
        return requestBody;
    }

    private Map<String, Object> buildStreamRequestBody(String prompt) {
        Map<String, Object> requestBody = buildRequestBody(prompt);
        requestBody.put("stream", true);
        return requestBody;
    }

    public SseEmitter streamCallLLM(String prompt) {
        SseEmitter emitter = new SseEmitter(120_000L);
        applicationContext.getBean(LLMService.class).executeStreamCall(prompt, emitter);
        return emitter;
    }

    @Async("detectionTaskExecutor")
    public void executeStreamCall(String prompt, SseEmitter emitter) {
        try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(llmConfig.getApiKey());

                byte[] bodyBytes = toJsonBytes(buildStreamRequestBody(prompt));

                restTemplate.execute(llmConfig.getApiUrl(), HttpMethod.POST,
                        req -> {
                            req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            req.getHeaders().setBearerAuth(llmConfig.getApiKey());
                            req.getBody().write(bodyBytes);
                        },
                        (ResponseExtractor<Void>) response -> {
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                StringBuilder fullContent = new StringBuilder();
                                while ((line = reader.readLine()) != null) {
                                    if (line.isEmpty()) continue;
                                    if (line.startsWith("data: ")) {
                                        String data = line.substring(6).trim();
                                        if ("[DONE]".equals(data)) break;
                                        String chunkContent = extractDeltaContent(data);
                                        if (!chunkContent.isEmpty()) {
                                            fullContent.append(chunkContent);
                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(chunkContent));
                                        }
                                    }
                                }
                                emitter.send(SseEmitter.event().name("done")
                                        .data(fullContent.toString()));
                                emitter.complete();
                            } catch (IOException e) {
                                log.error("SSE 流读取异常", e);
                                try {
                                    emitter.send(SseEmitter.event().name("error")
                                            .data("AI服务响应中断"));
                                } catch (IOException ignored) {
                                }
                                emitter.completeWithError(e);
                            }
                            return null;
                        });
            } catch (Exception e) {
                log.error("LLM 流式调用失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(FALLBACK_GENERAL));
                    emitter.complete();
                } catch (IOException ignored) {
                }
            }
    }

    private byte[] toJsonBytes(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            appendValue(sb, e.getValue());
        }
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void appendValue(StringBuilder sb, Object val) {
        if (val == null) {
            sb.append("null");
        } else if (val instanceof String) {
            String escaped = ((String) val)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f");
            sb.append("\"").append(escaped).append("\"");
        } else if (val instanceof Number || val instanceof Boolean) {
            sb.append(val);
        } else if (val instanceof List) {
            sb.append("[");
            List<?> list = (List<?>) val;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                appendValue(sb, list.get(i));
            }
            sb.append("]");
        } else if (val instanceof Map) {
            sb.append("{");
            Map<String, Object> m = (Map<String, Object>) val;
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":");
                appendValue(sb, e.getValue());
            }
            sb.append("}");
        }
    }

    private String extractDeltaContent(String json) {
        int deltaIdx = json.indexOf("\"delta\"");
        if (deltaIdx < 0) return "";
        int contentIdx = json.indexOf("\"content\"", deltaIdx);
        if (contentIdx < 0) return "";
        int colonIdx = json.indexOf(":", contentIdx);
        if (colonIdx < 0) return "";
        int startQuote = json.indexOf("\"", colonIdx);
        if (startQuote < 0) return "";
        int endQuote = json.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return "";
        String raw = json.substring(startQuote + 1, endQuote);
        return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    public SseEmitter streamScamSimulation(String userMessage, List<Message> history, ScamScenario scenario) {
        String effectiveMessage = userMessage == null ? "" : userMessage;
        String knowledge = buildKnowledgeContext(effectiveMessage);
        String prompt = promptLoader.loadPrompt("scam_simulation", Map.of(
                "scamScenario", scenario.getSystemPrompt(),
                "knowledge", knowledge,
                "conversationHistory", buildConversationHistory(history),
                "userMessage", effectiveMessage
        ));
        return streamCallLLM(prompt);
    }

    private String extractContent(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?> body)) {
            return invalidLLMResponse(responseBody);
        }

        Object choicesObj = body.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return invalidLLMResponse(responseBody);
        }

        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choice)) {
            return invalidLLMResponse(responseBody);
        }

        Object messageObj = choice.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            return invalidLLMResponse(responseBody);
        }

        Object content = message.get("content");
        return content instanceof String ? (String) content : invalidLLMResponse(responseBody);
    }

    private String invalidLLMResponse(Object responseBody) {
        log.error("大模型返回格式异常: {}", responseBody);
        return FALLBACK_GENERAL;
    }
}
