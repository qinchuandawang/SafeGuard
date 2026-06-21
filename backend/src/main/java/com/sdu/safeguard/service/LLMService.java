package com.sdu.safeguard.service;

import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.dto.Message;
import com.sdu.safeguard.dto.ScamScenario;
import com.sdu.safeguard.dto.TextDetectionResult;
import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.entity.KnowledgeItem;
import com.sdu.safeguard.util.PromptLoader;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper;
    @Qualifier("knowledgeContextCache")
    private final Cache<String, String> knowledgeContextCache;

    public String analyzeText(String text) {
        return callLLM(buildAnalyzePrompt(text));
    }

    /**
     * 结构化文本分析：调用 LLM，将 JSON 字符串解析为 TextDetectionResult。
     * 解析失败时降级为基于规则的兜底结果（非空）。
     */
    public TextDetectionResult analyzeTextStructured(String text) {
        TextDetectionResult result = new TextDetectionResult();
        String raw = callLLM(buildAnalyzePrompt(text));
        result.setReport(raw);

        // LLM 返回的可能不是合法 JSON 字符串，先尝试去掉 markdown 包裹
        String json = raw == null ? "" : raw.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline > 0) json = json.substring(firstNewline + 1);
            if (json.endsWith("```")) json = json.substring(0, json.length() - 3);
            json = json.trim();
        }
        // 清除 LLM 输出中可能出现的零宽字符（U+200B, U+200C, U+200D, U+FEFF）
        // 这些字符会导致 Jackson 解析失败
        json = json.replaceAll("[\\u200B\\u200C\\u200D\\uFEFF]", "");
        // 提取第一个 { 到最后一个 } 的 JSON 片段，丢弃前后杂质
        int firstBrace = json.indexOf('{');
        int lastBrace = json.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            json = json.substring(firstBrace, lastBrace + 1);
        }

        try {
            // 用 JsonNode 树模型宽容解析：字段类型不匹配（如 advice 是字符串而非数组）不抛错
            tools.jackson.databind.JsonNode root = objectMapper.readTree(json);

            TextDetectionResult parsed = new TextDetectionResult();
            parsed.setType("text");

            if (root.has("riskLevel") && root.get("riskLevel").isTextual()) {
                parsed.setRiskLevel(root.get("riskLevel").asText());
            }
            if (root.has("riskProbability") && root.get("riskProbability").isNumber()) {
                parsed.setRiskProbability(root.get("riskProbability").asDouble());
            }
            if (root.has("scamType") && root.get("scamType").isTextual()) {
                parsed.setScamType(root.get("scamType").asText());
            }
            parsed.setSuspiciousPoints(extractStringList(root, "suspiciousPoints"));
            parsed.setReasoningSteps(extractStringList(root, "reasoningSteps"));
            // advice 可能是数组或字符串
            if (root.has("advice")) {
                tools.jackson.databind.JsonNode adv = root.get("advice");
                if (adv.isArray()) {
                    parsed.setAdvice(extractStringList(root, "advice"));
                } else if (adv.isTextual()) {
                    String advText = adv.asText();
                    // 拆成多行建议
                    java.util.List<String> list = new java.util.ArrayList<>();
                    for (String line : advText.split("[\\n;；]")) {
                        String t = line.trim();
                        if (!t.isEmpty()) list.add(t);
                    }
                    if (list.isEmpty()) list.add(advText);
                    parsed.setAdvice(list);
                }
            }
            parsed.setReport(raw);
            parsed.computeProbabilities();
            return parsed;
        } catch (Exception e) {
            log.warn("文本分析结果 JSON 解析失败，使用降级结果: {}", e.getMessage());
            // 降级：基于规则生成基础结果
            result.setRiskLevel("medium");
            result.setRiskProbability(0.5);
            result.setScamType("未知");
            result.setSuspiciousPoints(java.util.List.of("无法解析 AI 分析结果，请查看原始报告"));
            result.setReasoningSteps(java.util.List.of("LLM 返回结果格式异常"));
            result.setAdvice(java.util.List.of("请人工核对原始报告内容"));
            result.computeProbabilities();
            return result;
        }
    }

    /**
     * 宽容提取 JSON 数组字段。数组元素若是字符串直接取；若是其他类型转字符串。
     */
    private java.util.List<String> extractStringList(tools.jackson.databind.JsonNode root, String fieldName) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (!root.has(fieldName)) return list;
        tools.jackson.databind.JsonNode node = root.get(fieldName);
        if (node.isArray()) {
            for (tools.jackson.databind.JsonNode item : node) {
                if (item.isTextual()) list.add(item.asText());
                else list.add(item.toString().replaceAll("^\"|\"$", ""));
            }
        } else if (node.isTextual()) {
            String t = node.asText().trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }

    /**
     * 基于音频检测结果生成一段自然语言的 AI 分析报告。
     * 当 LLM Key 未配置或调用失败时，返回基于规则的降级报告（非空）。
     */
    public String generateAudioReport(AudioDetectionResult audio) {
        if (audio == null) {
            return "未提供音频检测结果。";
        }
        if (llmConfig.getApiKey() == null || llmConfig.getApiKey().isBlank()) {
            return buildRuleBasedAudioReport(audio);
        }
        String label = audio.getLabel() == null ? "未知" : audio.getLabel();
        double fakeProb = audio.getSpoofProb() != null ? audio.getSpoofProb()
                : (audio.getFakeProbability() != null ? audio.getFakeProbability() : 0.0);
        double confidence = audio.getConfidence() != null ? audio.getConfidence() : fakeProb;

        String prompt = String.format(
                "你是音频伪造检测专家。请根据以下 Wav2Vec2 模型检测结果，用中文写一段 150-250 字的分析报告，"
                        + "解释为什么判定为该结果、风险点在哪里、给用户什么建议。直接输出报告正文，不要标题、不要 JSON。\n\n"
                        + "检测结果：\n- 判定标签：%s（bonafide=真实，spoof=伪造）\n"
                        + "- 伪造概率：%.1f%%\n- 置信度：%.1f%%\n- 风险等级：%s\n",
                label, fakeProb * 100, confidence * 100,
                audio.getRiskLevel() == null ? "未评估" : audio.getRiskLevel());
        return callLLM(prompt);
    }

    /**
     * LLM 不可用时的规则化降级报告。
     */
    private String buildRuleBasedAudioReport(AudioDetectionResult audio) {
        double fakeProb = audio.getSpoofProb() != null ? audio.getSpoofProb()
                : (audio.getFakeProbability() != null ? audio.getFakeProbability() : 0.0);
        boolean isSpoof = "spoof".equalsIgnoreCase(audio.getLabel()) || fakeProb > 0.5;
        StringBuilder sb = new StringBuilder();
        if (isSpoof) {
            sb.append("该音频被模型判定为【伪造】，伪造概率约 ")
                    .append(String.format("%.1f%%", fakeProb * 100))
                    .append("。");
            sb.append("Wav2Vec2 模型在频谱分布、声纹连贯性等维度检测到异常特征，")
                    .append("常见于 AI 语音克隆或拼接合成的伪造内容。");
            sb.append("建议：1）通过其他渠道核实对方身份；2）不要仅凭语音就进行转账；3）如有疑问拨打 96110 咨询。");
        } else {
            sb.append("该音频被模型判定为【真实】，伪造概率仅约 ")
                    .append(String.format("%.1f%%", fakeProb * 100))
                    .append("。");
            sb.append("未检测到明显的 AI 合成痕迹，但仍建议结合上下文综合判断，不可单凭一次检测下结论。");
        }
        return sb.toString();
    }

    /**
     * 基于视频检测结果生成一段自然语言的 AI 分析报告。
     * 当 LLM Key 未配置或调用失败时，返回基于规则的降级报告（非空）。
     */
    public String generateVideoReport(VideoDetectionResult video) {
        if (video == null) {
            return "未提供视频检测结果。";
        }
        if (llmConfig.getApiKey() == null || llmConfig.getApiKey().isBlank()) {
            return buildRuleBasedVideoReport(video);
        }
        double fakeProb = video.getFakeProbability() != null ? video.getFakeProbability() : 0.0;
        double confidence = video.getConfidence() != null ? video.getConfidence() : Math.abs(fakeProb - 0.5) * 2;
        int frameCount = video.getFrameAnalysis() != null ? video.getFrameAnalysis().size() : 0;
        String determination = fakeProb > 0.5 ? "伪造嫌疑较高" : "未发现明显伪造痕迹";

        String prompt = String.format(
                "你是视频换脸检测专家。本系统仅使用 XceptionNet 一种模型，不要在报告中推荐 EfficientNet、MesoNet、"
                        + "或其他本系统不存在的模型。请根据以下真实检测数据，用中文写一段 150-220 字的分析报告，"
                        + "直接输出报告正文，不要标题、不要 JSON、不要列表。\n\n"
                        + "重要提示：\n"
                        + "1) 伪造概率=0.0%% 表示模型判定为【真实】而非检测失败，请务必说明这是真实判定。\n"
                        + "2) 置信度=100%% 表示模型对【自己的判定结果】非常确信（0.5 两侧等价的 |p-0.5|*2 公式），"
                        + "   不是说'确定是假'，请勿让用户误读。\n"
                        + "3) 分析帧数=0 时，说明 XceptionNet 整图降级检测或直连 Flask 模式下聚合结果为空，"
                        + "   请不要写成'无法获取有效视频帧'，应该写成'模型对整张关键帧进行了检测'。\n\n"
                        + "检测结果：\n- 判定方向：%s\n- 伪造概率：%.1f%%\n- 置信度（模型对自己判定的确信程度）：%.1f%%\n- 分析人脸数：%d\n",
                determination, fakeProb * 100, confidence * 100, frameCount);
        return callLLM(prompt);
    }

    /**
     * LLM 不可用时的视频规则化降级报告。
     */
    private String buildRuleBasedVideoReport(VideoDetectionResult video) {
        double fakeProb = video.getFakeProbability() != null ? video.getFakeProbability() : 0.0;
        double confidence = video.getConfidence() != null ? video.getConfidence() : Math.abs(fakeProb - 0.5) * 2;
        int frameCount = video.getFrameAnalysis() != null ? video.getFrameAnalysis().size() : 0;
        boolean isFake = fakeProb > 0.5;
        StringBuilder sb = new StringBuilder();
        if (isFake) {
            sb.append("该视频被 XceptionNet 模型判定为【疑似换脸伪造】，伪造概率约 ")
                    .append(String.format("%.1f%%", fakeProb * 100))
                    .append("，模型对自己判定的确信程度约 ")
                    .append(String.format("%.1f%%", confidence * 100))
                    .append("。");
            sb.append("模型在 ").append(frameCount > 0 ? frameCount + " 张人脸" : "关键帧")
                    .append("上检测到与真实人脸不一致的纹理、边缘或频域异常，常见于 AI 换脸（Deepfake）或面部重演合成的伪造内容。");
            sb.append("建议：1）不要轻信视频中的人物身份，通过其他渠道核实；2）要求实时视频通话并让对方做转头、遮脸等动作；3）涉及转账或敏感操作时务必多重确认；4）如有疑问拨打 96110。");
        } else {
            sb.append("该视频被 XceptionNet 模型判定为【真实视频】，伪造概率约 ")
                    .append(String.format("%.1f%%", fakeProb * 100))
                    .append("，模型对自己判定的确信程度约 ")
                    .append(String.format("%.1f%%", confidence * 100))
                    .append("。");
            sb.append("模型在 ").append(frameCount > 0 ? frameCount + " 张人脸" : "关键帧")
                    .append("上未发现典型的 Deepfake 合成特征。");
            sb.append("注意：本系统仅使用 XceptionNet 一种模型，对抗性优化或新型扩散模型生成的视频可能产生误判，"
                    + "建议结合音频、文本等多模态信息综合判断，必要时通过电话或线下方式二次确认。");
        }
        return sb.toString();
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
        String cacheKey = keyword.toLowerCase().trim();
        String cached = knowledgeContextCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<KnowledgeItem> items = knowledgeService.search(keyword);
        if (items.isEmpty()) {
            knowledgeContextCache.put(cacheKey, "");
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
        String result = sb.toString();
        knowledgeContextCache.put(cacheKey, result);
        return result;
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
        if (llmConfig.getTemperature() != null) {
            requestBody.put("temperature", llmConfig.getTemperature());
        }
        if (llmConfig.getMaxTokens() != null) {
            requestBody.put("max_tokens", llmConfig.getMaxTokens());
        }
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

    @Async("streamExecutor")
    public void executeStreamCall(String prompt, SseEmitter emitter) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(llmConfig.getApiKey());

            byte[] bodyBytes = objectMapper.writeValueAsBytes(buildStreamRequestBody(prompt));

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
                            sendErrorAndComplete(emitter, null);
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.error("LLM 流式调用失败", e);
            sendErrorAndComplete(emitter, FALLBACK_GENERAL);
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String fallbackMessage) {
        try {
            if (fallbackMessage != null) {
                emitter.send(SseEmitter.event().name("error").data(fallbackMessage));
            }
            emitter.complete();
        } catch (IOException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private String extractDeltaContent(String json) {
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) return "";
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) return "";
            Object content = delta.get("content");
            return content != null ? content.toString() : "";
        } catch (Exception e) {
            log.debug("SSE delta 解析失败: {}", e.getMessage());
            return "";
        }
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
