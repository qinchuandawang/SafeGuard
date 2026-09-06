package com.sdu.safeguard.service;

import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.config.RAGConfig;
import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.dto.Message;
import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.dto.ScamScenario;
import com.sdu.safeguard.dto.SimulationResponse;
import com.sdu.safeguard.dto.TextDetectionResult;
import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.entity.KnowledgeItem;
import com.sdu.safeguard.rag.RuleFilter;
import com.sdu.safeguard.rag.agentic.AgenticRAGService;
import com.sdu.safeguard.util.PromptLoader;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

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
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final LLMConfig llmConfig;
    private final PromptLoader promptLoader;
    private final KnowledgeService knowledgeService;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    @Qualifier("knowledgeContextCache")
    private final Cache<String, String> knowledgeContextCache;
    @Qualifier("llmResponseCache")
    private final Cache<String, String> llmResponseCache;
    private final TokenCostService tokenCostService;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final AgenticRAGService agenticRagService;
    private final RuleFilter ruleFilter;
    private final RAGConfig ragConfig;
    private final SemanticLlmCache semanticLlmCache;

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${llm.cost-control.response-cache-ttl-seconds:600}")
    private long responseCacheTtlSeconds;

    public String analyzeText(String text) {
        return callLLM(buildAnalyzePrompt(text), "text-analysis");
    }

    public String askAssistant(String prompt) {
        return callLLM(prompt, "assistant");
    }

    /**
     * 统一的普通大模型调用入口。业务模块只声明场景，不直接持有供应商客户端。
     */
    public String complete(String prompt, String scene) {
        return callLLM(prompt, scene);
    }

    /**
     * 统一的 Function Calling 入口。工具结果依赖实时状态，不进入确定性响应缓存。
     */
    public String completeWithTools(String systemPrompt, String userPrompt,
                                    List<ToolCallback> callbacks, String scene) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            throw new IllegalStateException("Spring AI ChatClient 未配置");
        }
        int maxTokens = llmConfig.getMaxTokens() == null ? 0 : llmConfig.getMaxTokens();
        TokenCostService.Reservation reservation = tokenCostService.reserve(
                scene, llmConfig.getModel(), systemPrompt + "\n" + userPrompt, maxTokens * 3);
        try {
            String content = builder.build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .toolCallbacks(callbacks)
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("大模型返回空响应");
            }
            tokenCostService.complete(reservation, -1, -1, false, "success");
            return content;
        } catch (Exception exception) {
            tokenCostService.cancel(reservation, classifyLlmFailure(exception));
            throw new IllegalStateException("Function Calling 调用失败", exception);
        }
    }

    public String buildAssistantPrompt(String text) {
        String effectiveText = text == null ? "" : text.trim();
        return "你是 SafeGuard 反诈 AI 助手。请直接用自然中文回答用户问题，"
                + "不要输出 JSON、字段名、代码块、Markdown 表格或英文键名。"
                + "如果输入中包含“知识库内容”“用户问题”等上下文，只吸收其中有用事实，不要复述这些标签。"
                + "回答控制在 120 到 220 字，先给明确判断，再说明识别依据，最后给可执行建议。"
                + "如果信息不足，要说明需要补充哪些关键线索。\n\n"
                + "用户输入：\n" + effectiveText;
    }

    public String buildTextDetectionReport(TextDetectionResult result) {
        if (result == null) {
            return "大模型未返回有效文本分析结果，请重新提交内容。";
        }
        String report = cleanPlainReport(result.getReport());
        if (!report.isBlank()) {
            return report;
        }
        String riskLevel = switch (safeText(result.getRiskLevel(), "medium").toLowerCase()) {
            case "high" -> "高风险";
            case "low" -> "低风险";
            default -> "中等风险";
        };
        String scamType = safeText(result.getScamType(), "未知诈骗类型");
        StringBuilder sb = new StringBuilder();
        sb.append("本次文本检测判断为").append(riskLevel).append("，疑似类型为").append(scamType).append("。");
        if (result.getSuspiciousPoints() != null && !result.getSuspiciousPoints().isEmpty()) {
            sb.append("主要可疑点包括：").append(String.join("；", result.getSuspiciousPoints())).append("。");
        }
        if (result.getAdvice() != null && !result.getAdvice().isEmpty()) {
            sb.append("建议：").append(String.join("；", result.getAdvice())).append("。");
        } else {
            sb.append("建议暂停转账、付款或提供验证码，通过官方渠道核实对方身份。");
        }
        return sb.toString();
    }

    private String cleanPlainReport(String report) {
        if (report == null || report.isBlank()) {
            return "";
        }
        String text = report.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) text = text.substring(firstNewline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
            text = text.trim();
        }
        if (looksLikeJson(text)) {
            return "";
        }
        return text;
    }

    private boolean looksLikeJson(String value) {
        if (value == null) return false;
        String text = value.trim();
        return (text.startsWith("{") && text.endsWith("}"))
                || text.contains("\"riskLevel\"")
                || text.contains("\"riskProbability\"")
                || text.contains("\"suspiciousPoints\"")
                || text.contains("\"advice\"");
    }

    /**
     * 结构化文本分析：调用 LLM，将 JSON 字符串解析为 TextDetectionResult。
     * 解析失败时返回 LLM 异常兜底结果（非空）。
     */
    public TextDetectionResult analyzeTextStructured(String text) {
        TextDetectionResult result = new TextDetectionResult();
        if (llmConfig.getApiKey() == null || llmConfig.getApiKey().isBlank()) {
            result.setType("text");
            result.setRiskLevel("unknown");
            result.setRiskProbability(0.0);
            result.setScamType("大模型未配置");
            result.setReport("DeepSeek 大模型密钥未加载，无法完成文本语义分析。请检查 .env 中的 LLM_API_KEY 或 DEEPSEEK_API_KEY 是否已被启动脚本读取。");
            result.setSuspiciousPoints(java.util.List.of("大模型服务未配置，未执行真实语义检测"));
            result.setReasoningSteps(java.util.List.of("启动时未读取到 LLM API Key"));
            result.setAdvice(java.util.List.of("请先确认后端环境变量加载成功，再重新发起检测"));
            result.computeProbabilities();
            injectEvidence(result, text);
            return result;
        }
        String raw = callWithSemanticCache("text-analysis", text,
                () -> callLLM(buildAnalyzePrompt(text), "text-analysis"));
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
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);

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
            if (root.has("report") && root.get("report").isTextual()) {
                parsed.setReport(root.get("report").asText());
            } else {
                parsed.setReport(null);
            }
            parsed.setSuspiciousPoints(extractStringList(root, "suspiciousPoints"));
            parsed.setReasoningSteps(extractStringList(root, "reasoningSteps"));
            // advice 可能是数组或字符串
            if (root.has("advice")) {
                com.fasterxml.jackson.databind.JsonNode adv = root.get("advice");
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
            applyRiskBlend(parsed, text);
            parsed.setReport(buildTextDetectionReport(parsed));
            parsed.computeProbabilities();
            return parsed;
        } catch (Exception e) {
            log.warn("文本分析结果 JSON 解析失败，使用降级结果: {}", e.getMessage());
            // 降级：LLM 已调用但返回格式异常，生成可展示的中文报告
            result.setRiskLevel("medium");
            result.setRiskProbability(0.5);
            result.setScamType("未知");
            result.setSuspiciousPoints(java.util.List.of("无法解析 AI 分析结果，请查看原始报告"));
            result.setReasoningSteps(java.util.List.of("LLM 返回结果格式异常"));
            result.setAdvice(java.util.List.of("请人工核对原始报告内容"));
            applyRiskBlend(result, text);
            result.setReport(buildTextDetectionReport(result));
            result.computeProbabilities();
            return result;
        }
    }

    /**
     * 宽容提取 JSON 数组字段。数组元素若是字符串直接取；若是其他类型转字符串。
     */
    private java.util.List<String> extractStringList(com.fasterxml.jackson.databind.JsonNode root, String fieldName) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (!root.has(fieldName)) return list;
        com.fasterxml.jackson.databind.JsonNode node = root.get(fieldName);
        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
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
        double realProb = audio.getBonafideProb() != null ? audio.getBonafideProb() : Math.max(0.0, 1.0 - fakeProb);
        double confidence = audio.getConfidence() != null ? audio.getConfidence() : fakeProb;
        String thresholdNote = fakeProb >= 0.85
                ? "伪造概率超过 high 阈值 85%，模型认为存在较强伪造嫌疑"
                : (fakeProb >= 0.5
                ? "伪造概率超过 medium 阈值 50%，但未达到 high 阈值，需要结合通话场景复核"
                : "伪造概率低于 50%，模型当前更倾向于真实音频");

        String prompt = String.format(
                "你是 SafeGuard 的音频伪造检测解释专家。请只依据下面这一次 Wav2Vec2 模型真实输出写报告，"
                        + "不要编造未提供的频谱、声纹、情绪、说话人身份等细节。报告要贴合文件和数值，用中文 180-260 字，"
                        + "直接输出正文，不要标题、不要 JSON、不要列表。\n\n"
                        + "本次文件：%s，大小：%s。\n"
                        + "模型输出：label=%s（bonafide=真实，spoof=伪造），伪造概率=%.2f%%，真实概率=%.2f%%，置信度=%.2f%%，风险等级=%s。\n"
                        + "推理信息：模型版本=%s，设备=%s，推理耗时=%s ms。\n"
                        + "音频处理：原始采样率=%s Hz，模型采样率=%s Hz，声道数=%s，原始时长=%s 秒，实际分析=%s 秒，是否截断=%s。\n"
                        + "阈值解释：%s。\n\n"
                        + "写作要求：先说明模型更倾向真实还是伪造，再解释这个判断来自哪些数值；如果音频被截断，要提醒报告只覆盖已分析片段；"
                        + "最后给出适合普通用户的核验建议，例如通过原有联系方式复核、不要仅凭语音转账、必要时拨打 96110。",
                safeText(audio.getFileName(), "未命名音频"),
                formatBytes(audio.getFileSizeBytes()),
                label, fakeProb * 100, realProb * 100, confidence * 100,
                audio.getRiskLevel() == null ? "未评估" : audio.getRiskLevel(),
                safeText(audio.getModelVersion(), "未返回"),
                safeText(audio.getDevice(), "未返回"),
                audio.getLatencyMs() == null ? "未返回" : String.format("%.2f", audio.getLatencyMs()),
                audio.getOriginalSampleRate() == null ? "未返回" : audio.getOriginalSampleRate().toString(),
                audio.getSampleRate() == null ? "未返回" : audio.getSampleRate().toString(),
                audio.getChannels() == null ? "未返回" : audio.getChannels().toString(),
                audio.getDurationSeconds() == null ? "未返回" : String.format("%.2f", audio.getDurationSeconds()),
                audio.getAnalyzedSeconds() == null ? "未返回" : String.format("%.2f", audio.getAnalyzedSeconds()),
                Boolean.TRUE.equals(audio.getTruncated()) ? "是" : "否",
                thresholdNote);
        return callLLM(prompt, "audio-report");
    }

    /**
     * LLM 不可用时的规则化降级报告。
     */
    private String buildRuleBasedAudioReport(AudioDetectionResult audio) {
        double fakeProb = audio.getSpoofProb() != null ? audio.getSpoofProb()
                : (audio.getFakeProbability() != null ? audio.getFakeProbability() : 0.0);
        double realProb = audio.getBonafideProb() != null ? audio.getBonafideProb() : Math.max(0.0, 1.0 - fakeProb);
        double confidence = audio.getConfidence() != null ? audio.getConfidence() : fakeProb;
        boolean isSpoof = "spoof".equalsIgnoreCase(audio.getLabel()) || fakeProb > 0.5;
        StringBuilder sb = new StringBuilder();
        sb.append("本次音频文件");
        if (audio.getFileName() != null && !audio.getFileName().isBlank()) {
            sb.append("【").append(audio.getFileName()).append("】");
        }
        sb.append("由 Wav2Vec2 音频伪造检测模型完成推理。");
        if (isSpoof) {
            sb.append("模型判定方向为【疑似伪造】，伪造概率约 ")
                    .append(String.format("%.1f%%", fakeProb * 100))
                    .append("，真实概率约 ")
                    .append(String.format("%.1f%%", realProb * 100))
                    .append("，模型对该方向的置信度约 ")
                    .append(String.format("%.1f%%", confidence * 100))
                    .append("。");
            sb.append("该结论来自模型输出概率，不是后端固定规则。");
        } else {
            sb.append("模型判定方向为【更接近真实音频】，伪造概率约 ")
                    .append(String.format("%.1f%%", fakeProb * 100))
                    .append("，真实概率约 ")
                    .append(String.format("%.1f%%", realProb * 100))
                    .append("，模型置信度约 ")
                    .append(String.format("%.1f%%", confidence * 100))
                    .append("。");
            sb.append("当前数值低于伪造阈值，但仍需结合通话内容和资金请求综合判断。");
        }
        if (audio.getDurationSeconds() != null || audio.getAnalyzedSeconds() != null) {
            sb.append("音频原始时长约 ")
                    .append(audio.getDurationSeconds() == null ? "未知" : String.format("%.2f", audio.getDurationSeconds()))
                    .append(" 秒，实际分析约 ")
                    .append(audio.getAnalyzedSeconds() == null ? "未知" : String.format("%.2f", audio.getAnalyzedSeconds()))
                    .append(" 秒");
            if (Boolean.TRUE.equals(audio.getTruncated())) {
                sb.append("，长音频已按模型上限截断");
            }
            sb.append("。");
        }
        sb.append("建议通过原有联系方式核实对方身份，不要仅凭语音进行转账或验证码操作；如涉及钱款或冒充熟人，可拨打 96110 咨询。");
        return sb.toString();
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String formatBytes(Long value) {
        if (value == null || value < 0) return "未返回";
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format("%.1f KB", value / 1024.0);
        return String.format("%.1f MB", value / 1024.0 / 1024.0);
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
        double visualFakeProb = video.getVisualFakeProbability() != null ? video.getVisualFakeProbability() : fakeProb;
        double confidence = video.getConfidence() != null ? video.getConfidence() : Math.abs(fakeProb - 0.5) * 2;
        int frameCount = video.getFrameAnalysis() != null ? video.getFrameAnalysis().size() : 0;
        double avgProb = video.getAverageFakeProbability() != null ? video.getAverageFakeProbability() : fakeProb;
        double maxProb = video.getMaxFakeProbability() != null ? video.getMaxFakeProbability() : fakeProb;
        double suspiciousRatio = video.getSuspiciousFrameRatio() != null ? video.getSuspiciousFrameRatio() : 0.0;
        boolean aigcMetadataDetected = Boolean.TRUE.equals(video.getAigcMetadataDetected());
        String evidenceText = video.getEvidenceReasons() == null || video.getEvidenceReasons().isEmpty()
                ? "无"
                : String.join("；", video.getEvidenceReasons());
        String frameSummary = buildVideoFrameSummary(video);
        String suspiciousFrames = buildSuspiciousFrameSummary(video);
        String overallDetermination = "uncertain".equals(video.getDetermination())
                ? "无法判定（视频中未检测到人脸）"
                : (fakeProb > 0.5 ? "伪造嫌疑较高" : "未发现明显伪造痕迹");
        String visualDetermination = buildVisualDetermination(visualFakeProb, maxProb, suspiciousRatio);

        String prompt = String.format(
                "你是视频换脸检测专家。本系统仅使用 XceptionNet 一种模型，不要在报告中推荐 EfficientNet、MesoNet、"
                        + "或其他本系统不存在的模型。请根据以下真实检测数据，用中文写一段 180-260 字的分析报告，"
                        + "直接输出报告正文，不要标题、不要 JSON、不要列表。\n\n"
                        + "重要提示：\n"
                        + "1) 低伪造概率只能表示【当前模型未发现明显伪造证据】，不能写成“百分之百真实”“没有争议”或“证明真实”。\n"
                        + "2) 置信度=100%% 表示模型对【自己的判定结果】非常确信（0.5 两侧等价的 |p-0.5|*2 公式），"
                        + "   不是说'确定真实'或'确定是假'，请勿让用户误读。\n"
                        + "3) 当判定方向是'无法判定'时，说明视频中未检测到人脸，必须告诉用户重新提交清晰的正脸视频，"
                        + "   不要写成'无法获取有效视频帧'。\n\n"
                        + "4) 如果 AIGC 元数据命中为 true，必须明确说明文件元数据包含 AIGC/生成来源标记，"
                        + "   这是强证据；即使视觉模型分数不高，也应按疑似 AI 生成内容提醒用户。\n\n"
                        + "5) 报告必须体现本视频的差异化数据：写清楚共切分/分析了多少帧，点名最可疑的第几帧及其可疑度；"
                        + "如果没有超过 50%% 的帧，也要说明相对最高的帧是哪几帧，不能泛泛而谈。\n\n"
                        + "6) 必须区分【视觉模型结论】和【综合结论】：如果视觉模型风险接近 0 但 AIGC 元数据命中，"
                        + "   只能写“视觉模型未发现明显伪造风险，但综合元数据后判定高风险”，不能写“视觉模型判定高风险”。\n\n"
                        + "7) 如果最高单帧伪造概率超过 50%%，不能写“视觉模型未发现明显伪造风险”；应写“整体平均不高，但第 X 帧等局部帧超过阈值，存在局部可疑信号”。\n\n"
                        + "8) XceptionNet 是换脸/面部篡改视觉模型，不是通用视频大模型；如果用户视频是豆包等 AIGC 整体生成视频，"
                        + "   视觉换脸风险低不代表不是 AI 生成，只代表未检测到明显换脸痕迹。\n\n"
                        + "检测结果：\n- 综合判定方向：%s\n- 视觉模型结论：%s\n- 综合风险概率：%.1f%%\n- 视觉换脸风险概率：%.1f%%\n- 置信度（模型对综合判定的确信程度）：%.1f%%\n- 分析人脸数：%d\n",
                overallDetermination + String.format("\n- 平均伪造概率：%.1f%%\n- 最高单帧伪造概率：%.1f%%\n- 可疑帧占比：%.1f%%",
                        avgProb * 100, maxProb * 100, suspiciousRatio * 100)
                        + "\n- AIGC 元数据命中：" + (aigcMetadataDetected ? "true" : "false")
                        + "\n- 元数据证据：" + evidenceText
                        + "\n- 逐帧伪造概率：" + frameSummary
                        + "\n- 最可疑帧：" + suspiciousFrames,
                visualDetermination, fakeProb * 100, visualFakeProb * 100, confidence * 100, frameCount);
        return callLLM(prompt, "video-report");
    }

    private String buildVideoFrameSummary(VideoDetectionResult video) {
        List<VideoDetectionResult.FrameAnalysis> frames = video.getFrameAnalysis();
        if (frames == null || frames.isEmpty()) {
            return "未返回逐帧概率";
        }
        return frames.stream()
                .sorted((a, b) -> Integer.compare(
                        a.getFrameIndex() == null ? 0 : a.getFrameIndex(),
                        b.getFrameIndex() == null ? 0 : b.getFrameIndex()))
                .map(frame -> "第" + displayFrameIndex(frame) + "帧 "
                        + String.format("%.1f%%", safeProbability(frame.getFakeProbability()) * 100))
                .reduce((left, right) -> left + "，" + right)
                .orElse("未返回逐帧概率");
    }

    private String buildSuspiciousFrameSummary(VideoDetectionResult video) {
        List<VideoDetectionResult.FrameAnalysis> frames = video.getFrameAnalysis();
        if (frames == null || frames.isEmpty()) {
            return "无逐帧数据";
        }
        List<VideoDetectionResult.FrameAnalysis> sorted = frames.stream()
                .sorted((a, b) -> Double.compare(
                        safeProbability(b.getFakeProbability()),
                        safeProbability(a.getFakeProbability())))
                .limit(3)
                .toList();
        String topFrames = sorted.stream()
                .map(frame -> "第" + displayFrameIndex(frame) + "帧 "
                        + String.format("%.1f%%", safeProbability(frame.getFakeProbability()) * 100))
                .reduce((left, right) -> left + "，" + right)
                .orElse("无逐帧数据");
        long aboveThreshold = frames.stream()
                .filter(frame -> safeProbability(frame.getFakeProbability()) >= 0.5)
                .count();
        if (aboveThreshold > 0) {
            return topFrames + "；其中 " + aboveThreshold + " 帧超过 50% 可疑阈值";
        }
        return topFrames + "；没有单帧超过 50% 可疑阈值，以上为相对最高的帧";
    }

    private String buildVisualDetermination(double visualFakeProb, double maxProb, double suspiciousRatio) {
        if (visualFakeProb >= 0.55 || suspiciousRatio >= 0.25) {
            return "视觉模型发现明显伪造风险";
        }
        if (maxProb >= 0.5) {
            return "视觉模型整体风险不高，但存在超过 50% 阈值的局部可疑帧";
        }
        if (maxProb >= 0.4) {
            return "视觉模型未形成高风险结论，但存在相对可疑帧，需结合来源继续核验";
        }
        return "视觉模型未发现明显换脸伪造风险";
    }

    private int displayFrameIndex(VideoDetectionResult.FrameAnalysis frame) {
        return (frame.getFrameIndex() == null ? 0 : frame.getFrameIndex()) + 1;
    }

    private double safeProbability(Double probability) {
        if (probability == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, probability));
    }

    /**
     * LLM 不可用时的视频规则化降级报告。
     */
    private String buildRuleBasedVideoReport(VideoDetectionResult video) {
        double fakeProb = video.getFakeProbability() != null ? video.getFakeProbability() : 0.0;
        double visualFakeProb = video.getVisualFakeProbability() != null ? video.getVisualFakeProbability() : fakeProb;
        double confidence = video.getConfidence() != null ? video.getConfidence() : Math.abs(fakeProb - 0.5) * 2;
        int frameCount = video.getFrameAnalysis() != null ? video.getFrameAnalysis().size() : 0;
        double avgProb = video.getAverageFakeProbability() != null ? video.getAverageFakeProbability() : fakeProb;
        double maxProb = video.getMaxFakeProbability() != null ? video.getMaxFakeProbability() : fakeProb;
        double suspiciousRatio = video.getSuspiciousFrameRatio() != null ? video.getSuspiciousFrameRatio() : 0.0;
        String det = video.getDetermination();
        boolean isUncertain = "uncertain".equals(det);
        String suspiciousFrames = buildSuspiciousFrameSummary(video);
        StringBuilder sb = new StringBuilder();
        if (Boolean.TRUE.equals(video.getAigcMetadataDetected())) {
            sb.append("该视频文件元数据命中 AIGC 或生成来源标记，被系统作为强证据判定为【疑似 AI 生成内容】。");
            if (video.getEvidenceReasons() != null && !video.getEvidenceReasons().isEmpty()) {
                sb.append("证据包括：").append(String.join("；", video.getEvidenceReasons())).append("。");
            }
            sb.append("视觉模型综合风险约 ").append(String.format("%.1f%%", visualFakeProb * 100))
                    .append("，平均伪造概率约 ").append(String.format("%.1f%%", avgProb * 100))
                    .append("，最高单帧伪造概率约 ").append(String.format("%.1f%%", maxProb * 100))
                    .append("，最可疑帧为").append(suspiciousFrames)
                    .append("；即使画面层面未出现明显换脸痕迹，文件元数据仍表明其很可能来自生成式视频流程。")
                    .append("建议不要把该视频作为身份、事件或转账依据，应要求对方提供原始来源并通过实时通话、线下核验等方式确认。");
            return sb.toString();
        }
        if (isUncertain) {
            sb.append("本次检测【无法判定】视频真伪。原因是 XceptionNet 模型在所有抽样帧中均未检测到清晰的人脸区域，")
                    .append("无法对人脸特征进行换脸痕迹分析。");
            sb.append("建议：1）请重新提交时长不少于 2 秒、人物正脸清晰、")
                    .append("光线正常、画面稳定的视频；2）避免使用风景、动物、纯文字等无人脸内容；")
                    .append("3）若有疑问可结合音频、文本等多模态信息综合判断。");
        } else {
            boolean isFake = fakeProb > 0.5;
            boolean hasLocalSuspiciousFrame = maxProb >= 0.5;
            if (isFake) {
                sb.append("该视频被 XceptionNet 模型判定为【疑似换脸伪造】，伪造概率约 ")
                        .append(String.format("%.1f%%", fakeProb * 100))
                        .append("，模型对自己判定的确信程度约 ")
                        .append(String.format("%.1f%%", confidence * 100))
                        .append("。");
                sb.append("模型在 ").append(frameCount > 0 ? frameCount + " 张人脸" : "关键帧")
                        .append("上检测到与真实人脸不一致的纹理、边缘或频域异常，常见于 AI 换脸（Deepfake）或面部重演合成的伪造内容。");
                sb.append("建议：1）不要轻信视频中的人物身份，通过其他渠道核实；2）要求实时视频通话并让对方做转头、遮脸等动作；3）涉及转账或敏感操作时务必多重确认；4）如有疑问拨打 96110。");
            } else if (hasLocalSuspiciousFrame) {
                sb.append("该视频的整体视觉风险未达到高风险阈值，但不能简单判定为无风险。")
                        .append("本次共分析 ").append(frameCount).append(" 个关键帧，平均伪造概率约 ")
                        .append(String.format("%.1f%%", avgProb * 100))
                        .append("，最高单帧伪造概率约 ")
                        .append(String.format("%.1f%%", maxProb * 100))
                        .append("，最可疑帧为").append(suspiciousFrames).append("。");
                sb.append("这说明 XceptionNet 在局部帧上捕捉到了超过阈值的可疑信号，但由于可疑帧占比或整体聚合分数不足，尚未形成稳定的换脸高风险结论。");
                sb.append("建议结合视频来源、音频、文本话术和元数据继续核验，不要把该视频作为身份或事实真实性的唯一依据。");
            } else {
                sb.append("该视频被 XceptionNet 模型判定为【真实视频】，伪造概率约 ")
                        .append(String.format("%.1f%%", fakeProb * 100))
                        .append("，模型对自己判定的确信程度约 ")
                        .append(String.format("%.1f%%", confidence * 100))
                        .append("。");
                sb.append("模型平均伪造概率约 ").append(String.format("%.1f%%", avgProb * 100))
                        .append("，最高单帧伪造概率约 ").append(String.format("%.1f%%", maxProb * 100))
                        .append("，可疑帧占比约 ").append(String.format("%.1f%%", suspiciousRatio * 100)).append("。");
                sb.append("这只能说明模型在 ").append(frameCount > 0 ? frameCount + " 张人脸" : "关键帧")
                        .append("上未发现足够明显的 Deepfake 合成特征，不能证明视频百分之百真实。");
                sb.append("注意：本系统仅使用 XceptionNet 一种模型，对抗性优化、压缩转码或新型扩散模型生成的视频可能产生误判，"
                        + "建议结合音频、文本等多模态信息综合判断，必要时通过电话或线下方式二次确认。");
            }
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

    public String analyzeMultiModalEvidence(String text, Object textResult, Object audioResult,
                                            Object videoResult, Object workflow) {
        String effectiveText = text != null ? text : DEFAULT_TEXT;
        String knowledge = buildKnowledgeContext(effectiveText);
        return callLLM(promptLoader.loadPrompt("multimodal_analysis", Map.of(
                "text", effectiveText,
                "textResult", textResult != null ? textResult : Map.of(),
                "knowledge", knowledge,
                "audio", audioResult != null ? audioResult : Map.of(),
                "video", videoResult != null ? videoResult : Map.of(),
                "workflow", workflow != null ? workflow : Map.of()
        )), "multimodal");
    }

    public String scamSimulation(String userMessage, List<Message> history, ScamScenario scenario) {
        String effectiveMessage = userMessage == null ? "" : userMessage;
        String knowledge = buildKnowledgeContext(effectiveMessage);
        return callWithSemanticCache("simulation:" + scenario.name(), effectiveMessage,
                () -> callLLM(promptLoader.loadPrompt("scam_simulation", Map.of(
                        "scamScenario", scenario.getSystemPrompt(),
                        "knowledge", knowledge,
                        "conversationHistory", buildConversationHistory(history),
                        "userMessage", effectiveMessage
                )), "simulation"));
    }

    public SimulationResponse scamSimulationStructured(String userMessage, List<Message> history, ScamScenario scenario) {
        String raw = scamSimulation(userMessage, history, scenario);
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(sanitizeJsonLike(raw));
            SimulationResponse response = new SimulationResponse();
            response.setContent(readText(root, "content", raw));
            response.setQuickReplies(readStringList(root, "quickReplies"));
            if (response.getQuickReplies().isEmpty()) {
                response.setQuickReplies(defaultQuickReplies(scenario));
            }
            if (root.has("suspicionDelta") && root.get("suspicionDelta").isNumber()) {
                response.setSuspicionDelta(Math.max(-25, Math.min(25, root.get("suspicionDelta").asInt())));
            } else {
                response.setSuspicionDelta(guessSuspicionDelta(userMessage));
            }
            response.setFinished(root.has("finished") && root.get("finished").asBoolean(false));
            response.setAnalysis(readText(root, "analysis", ""));
            response.setTips(readStringList(root, "tips"));
            if (Boolean.TRUE.equals(response.getFinished()) && response.getTips().isEmpty()) {
                response.setTips(defaultDefenseTips());
            }
            return response;
        } catch (Exception e) {
            log.warn("模拟诈骗响应解析失败，使用文本兜底: {}", e.getMessage());
            SimulationResponse fallback = new SimulationResponse();
            fallback.setContent(stripJsonNoise(raw));
            fallback.setQuickReplies(defaultQuickReplies(scenario));
            fallback.setSuspicionDelta(guessSuspicionDelta(userMessage));
            fallback.setFinished(looksSafeStop(userMessage));
            if (Boolean.TRUE.equals(fallback.getFinished())) {
                fallback.setAnalysis("你已经表现出较强警觉性，主动提出核实身份、拒绝转账或联系官方渠道，这是识别诈骗的关键。");
                fallback.setTips(defaultDefenseTips());
            }
            return fallback;
        }
    }

    private String sanitizeJsonLike(String raw) {
        if (raw == null) {
            return "{}";
        }
        String cleaned = raw.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("\\s*```$", "");
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private String readText(com.fasterxml.jackson.databind.JsonNode root, String field, String fallback) {
        if (root != null && root.has(field) && root.get(field).isTextual()) {
            String value = root.get(field).asText();
            return value == null || value.isBlank() ? fallback : value.trim();
        }
        return fallback == null ? "" : fallback;
    }

    private List<String> readStringList(com.fasterxml.jackson.databind.JsonNode root, String field) {
        if (root == null || !root.has(field) || !root.get(field).isArray()) {
            return new java.util.ArrayList<>();
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        root.get(field).forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        });
        return values;
    }

    private List<String> defaultQuickReplies(ScamScenario scenario) {
        return switch (scenario) {
            case IMPERSONATE_POLICE -> List.of("请出示警号和单位", "我打110核实", "我不会转账");
            case CUSTOMER_SERVICE -> List.of("我去官方App查看", "退款为什么要验证码", "不要再联系我");
            case INVESTMENT -> List.of("收益凭证发我看看", "我不投陌生平台", "我先问家人");
            case BRUSH_ORDER -> List.of("要垫钱我不做", "可以提现吗", "我先查平台资质");
            default -> List.of("你先语音通话", "我打你原号码核实", "我现在不转钱");
        };
    }

    private int guessSuspicionDelta(String userMessage) {
        String value = userMessage == null ? "" : userMessage;
        if (looksSafeStop(value)) {
            return 18;
        }
        if (value.contains("转") || value.contains("验证码") || value.contains("银行卡") || value.contains("链接")) {
            return -12;
        }
        if (value.contains("为什么") || value.contains("怎么") || value.contains("详细")) {
            return 3;
        }
        return 6;
    }

    private boolean looksSafeStop(String userMessage) {
        String value = userMessage == null ? "" : userMessage;
        return value.contains("核实")
                || value.contains("官方")
                || value.contains("报警")
                || value.contains("96110")
                || value.contains("110")
                || value.contains("不转")
                || value.contains("验证码不给")
                || value.contains("拒绝");
    }

    private List<String> defaultDefenseTips() {
        return List.of("涉及转账、验证码、屏幕共享时立即停止。", "通过官方 App、官网电话或 96110 核实身份。", "保留聊天和转账证据，必要时及时报警。");
    }

    private String stripJsonNoise(String raw) {
        if (raw == null || raw.isBlank()) {
            return "你现在方便吗？这边情况有点急，需要你先配合我确认一下。";
        }
        return raw.replaceAll("```json|```", "").trim();
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
        // RAG 优先：Agentic RAG（Qdrant 混合检索）作为核心检测的知识上下文
        String result = buildRagKnowledgeContext(keyword);
        if (result.isBlank()) {
            // 回退：MySQL 关键词知识检索
            result = buildKeywordKnowledgeContext(keyword);
        }
        knowledgeContextCache.put(cacheKey, result);
        return result;
    }

    /**
     * 通过 Agentic RAG 获取反诈知识上下文。失败时返回空串触发回退。
     */
    private String buildRagKnowledgeContext(String keyword) {
        try {
            List<RagQueryResult> results = agenticRagService.query(keyword);
            if (results == null || results.isEmpty()) {
                return "";
            }
            return agenticRagService.formatRagContext(results);
        } catch (Exception e) {
            log.warn("RAG 知识检索失败，回退 MySQL 关键词检索: {}", e.getMessage());
            return "";
        }
    }

    private String buildKeywordKnowledgeContext(String keyword) {
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

    /**
     * 知识增强风险混合：LLM 概率不确定（|p-0.5| &lt; 带宽）且规则/RAG 风险分足够高时，
     * 用规则风险分做辅助抬升，并将规则与知识证据注入检测结果。这是 RAG 融入核心
     * 检测的辅助信号层，不改变模型本身的判定逻辑。
     */
    private void applyRiskBlend(TextDetectionResult result, String text) {
        injectEvidence(result, text);
        if (result == null || result.getRiskProbability() == null) {
            return;
        }
        if (!ragConfig.isRiskBlendEnabled()) {
            return;
        }
        RuleFilter.RuleResult rule = ruleFilter.match(text);
        if (!rule.isMatched()) {
            return;
        }
        double ruleScore = rule.getRiskScore();
        double p = result.getRiskProbability();
        if (Math.abs(p - 0.5) >= ragConfig.getRiskBlendUncertaintyBand()) {
            return;
        }
        if (ruleScore < ragConfig.getRiskBlendRuleFloor()) {
            return;
        }
        double blended = Math.max(p, ruleScore * ragConfig.getRiskBlendWeight());
        result.setRiskProbability(blended);
        result.setRuleEscalated(true);
        if (blended >= 0.55) {
            result.setRiskLevel("high");
        }
        result.computeProbabilities();
    }

    /**
     * 将规则过滤与知识库命中的关键词作为证据注入检测结果（解释/审计层）。
     */
    private void injectEvidence(TextDetectionResult result, String text) {
        if (result == null) {
            return;
        }
        RuleFilter.RuleResult rule = ruleFilter.match(text);
        result.setRuleRiskScore(rule.isMatched() ? rule.getRiskScore() : 0.0);
        result.setMatchedRules(rule.isMatched()
                ? rule.getMatchedKeywords()
                : java.util.List.of());
        try {
            result.setKnowledgeEvidence(new java.util.ArrayList<>(agenticRagService.matchKeywords(text)));
        } catch (Exception e) {
            result.setKnowledgeEvidence(java.util.List.of());
        }
    }

    /**
     * 语义缓存包装：语义相近（同场景、高相似度）的重复查询直接复用缓存回复，
     * 命中即零 LLM 调用；未命中时正常调用并写入缓存（失败/降级结果不入缓存）。
     */
    private String callWithSemanticCache(String scene, String query,
                                         java.util.function.Supplier<String> executor) {
        try {
            String cached = semanticLlmCache.get(scene, query);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            log.debug("LLM 语义缓存读取失败，跳过: {}", e.getMessage());
        }
        String result = executor.get();
        if (result != null && !isFallback(result)) {
            try {
                semanticLlmCache.put(scene, query, result);
            } catch (Exception e) {
                log.debug("LLM 语义缓存写入失败，忽略: {}", e.getMessage());
            }
        }
        return result;
    }

    private String callLLM(String prompt, String scene) {
        String cacheKey = responseCacheKey(prompt, scene);
        if (tokenCostService.isCacheable(scene)) {
            String cached = getCachedResponse(cacheKey);
            if (cached != null) {
                TokenCostService.Reservation cacheReservation = tokenCostService.reserve(
                        scene, llmConfig.getModel(), "", 0);
                tokenCostService.complete(cacheReservation, 0, 0, true, "success");
                return cached;
            }
        }
        int maxCompletionTokens = llmConfig.getMaxTokens() == null ? 0 : llmConfig.getMaxTokens();
        TokenCostService.Reservation reservation = tokenCostService.reserve(
                scene, llmConfig.getModel(), prompt, maxCompletionTokens);
        try {
            ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
            if (builder == null) {
                tokenCostService.cancel(reservation, "spring_ai_unavailable");
                log.error("Spring AI ChatClient 未配置，无法执行同步大模型调用");
                return FALLBACK_GENERAL;
            }
            String content = builder.build()
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                tokenCostService.cancel(reservation, "empty_response");
                log.error("Spring AI 返回空响应: scene={}", scene);
                return FALLBACK_GENERAL;
            }
            tokenCostService.complete(reservation, -1, -1, false, "success");
            if (tokenCostService.isCacheable(scene) && !isFallback(content)) {
                putCachedResponse(cacheKey, content);
            }
            return content;
        } catch (Exception exception) {
            tokenCostService.cancel(reservation, classifyLlmFailure(exception));
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("429")) {
                log.error("LLM API 限流(429)，重试耗尽");
                return FALLBACK_RATE_LIMIT;
            }
            if (message.toLowerCase().contains("timeout") || message.contains("超时")) {
                log.error("LLM API 连接超时，重试耗尽: {}", message);
                return FALLBACK_TIMEOUT;
            }
            if (message.contains("500") || message.contains("502") || message.contains("503")) {
                log.error("LLM API 服务端错误，重试耗尽: {}", message);
                return FALLBACK_SERVER_ERROR;
            }
            log.error("Spring AI LLM 调用失败: {}", message);
            return FALLBACK_GENERAL;
        }
    }

    private String classifyLlmFailure(Exception exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("429")) return "rate_limit";
        if (message.toLowerCase().contains("timeout") || message.contains("超时")) return "timeout";
        if (message.contains("500") || message.contains("502") || message.contains("503")) return "server_error";
        return "error";
    }

    private boolean isFallback(String content) {
        return FALLBACK_GENERAL.equals(content) || FALLBACK_RATE_LIMIT.equals(content)
                || FALLBACK_TIMEOUT.equals(content) || FALLBACK_SERVER_ERROR.equals(content);
    }

    private String responseCacheKey(String prompt, String scene) {
        String source = llmConfig.getModel() + "|" + tokenCostService.promptVersion() + "|"
                + scene + "|" + llmConfig.getTemperature() + "|" + llmConfig.getMaxTokens() + "|" + prompt;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("生成 LLM 缓存键失败", e);
        }
    }

    private String getCachedResponse(String cacheKey) {
        String local = llmResponseCache.getIfPresent(cacheKey);
        if (local != null || !redisEnabled) return local;
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            String shared = redisTemplate == null ? null
                    : redisTemplate.opsForValue().get("safeguard:cache:llm:" + cacheKey);
            if (shared != null) llmResponseCache.put(cacheKey, shared);
            return shared;
        } catch (Exception e) {
            return null;
        }
    }

    private void putCachedResponse(String cacheKey, String content) {
        llmResponseCache.put(cacheKey, content);
        if (!redisEnabled) return;
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                redisTemplate.opsForValue().set("safeguard:cache:llm:" + cacheKey,
                        content, Duration.ofSeconds(responseCacheTtlSeconds));
            }
        } catch (Exception e) {
            log.debug("写入共享 LLM 响应缓存失败: {}", e.getMessage());
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
        int maxCompletionTokens = llmConfig.getMaxTokens() == null ? 0 : llmConfig.getMaxTokens();
        TokenCostService.Reservation reservation;
        try {
            reservation = tokenCostService.reserve("stream", llmConfig.getModel(), prompt, maxCompletionTokens);
        } catch (TokenBudgetExceededException e) {
            sendErrorAndComplete(emitter, e.getMessage());
            return;
        }
        AtomicBoolean accounted = new AtomicBoolean();
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
                            tokenCostService.complete(reservation, null,
                                    tokenCostService.estimateTokens(fullContent.toString()), false, "success");
                            accounted.set(true);
                            emitter.complete();
                        } catch (IOException e) {
                            tokenCostService.cancel(reservation, "client_disconnected");
                            accounted.set(true);
                            log.error("SSE 流读取异常", e);
                            sendErrorAndComplete(emitter, null);
                        }
                        return null;
                    });
        } catch (Exception e) {
            if (accounted.compareAndSet(false, true)) {
                tokenCostService.cancel(reservation, "error");
            }
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

}
