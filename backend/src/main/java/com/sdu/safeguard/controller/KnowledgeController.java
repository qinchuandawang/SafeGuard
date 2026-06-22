package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.ChatRequest;
import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.KnowledgeItem;
import com.sdu.safeguard.dto.ScamScenario;
import com.sdu.safeguard.dto.SimulationResponse;
import com.sdu.safeguard.service.KnowledgeService;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.util.InputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeController {

    private static final int MAX_IMPORT_TEXT_LENGTH = 20000;

    private final KnowledgeService knowledgeService;
    private final LLMService llmService;

    @GetMapping("/knowledge/search")
    public Result<List<KnowledgeItem>> search(@RequestParam(value = "keyword", required = false) String keyword,
                                               @RequestParam(value = "q", required = false) String q) {
        String kw = keyword != null ? keyword : q;
        // 限长校验：防 DoS（避免大 keyword 直接打到 LIKE 全模糊匹配 + 内存中字符串拼接）
        String validationError = InputValidator.validateKeyword(kw);
        if (validationError != null) {
            return Result.badRequest(validationError);
        }
        List<KnowledgeItem> result = knowledgeService.search(kw.trim());
        return Result.success(result);
    }

    @GetMapping("/knowledge/category/{category}")
    public Result<List<KnowledgeItem>> getByCategory(@PathVariable String category) {
        List<KnowledgeItem> result = knowledgeService.getByCategory(category);
        return Result.success(result);
    }

    @GetMapping("/knowledge")
    public Result<List<KnowledgeItem>> getAll() {
        return Result.success(knowledgeService.getAll());
    }

    /** 小程序知识库文章列表格式 */
    @GetMapping("/knowledge/articles")
    public Result<List<Map<String, Object>>> getArticles() {
        List<KnowledgeItem> items = knowledgeService.getAll();
        List<Map<String, Object>> articles = new ArrayList<>();
        // 封面图轮播
        String[] covers = {"1.jpg","2.jpg","3.jpg","4.jpg","5.jpg","6.jpg","7.jpg","8.jpg"};
        for (int i = 0; i < items.size(); i++) {
            KnowledgeItem item = items.get(i);
            if (item.getEnabled() != null && !item.getEnabled()) continue;
            Map<String, Object> article = new LinkedHashMap<>();
            article.put("id", item.getId());
            article.put("title", item.getQuestion());
            String summary = item.getAnswer() != null ? item.getAnswer() : "";
            article.put("summary", summary.length() > 120 ? summary.substring(0, 120) + "…" : summary);
            article.put("content", summary);
            article.put("answer", summary);
            article.put("cover", "/assets/images/covers/" + covers[i % covers.length]);
            article.put("tags", item.getTags() != null ? List.of(item.getTags().split(",")) : List.of());
            article.put("category", item.getCategory());
            article.put("date", java.time.LocalDate.now().toString());
            // 浏览量：基于 id 的稳定值（5000-34999 范围），后续接入真实统计时再替换
            long baseViews = 5000L + (item.getId() != null ? (item.getId() * 1373L) % 30000L : 0L);
            article.put("views", (int) baseViews);
            articles.add(article);
        }
        return Result.success(articles);
    }

    @PostMapping("/knowledge")
    public Result<KnowledgeItem> create(@RequestBody KnowledgeItem item) {
        if (item.getQuestion() == null || item.getQuestion().trim().isEmpty()) {
            return Result.error("问题不能为空");
        }
        if (item.getAnswer() == null || item.getAnswer().trim().isEmpty()) {
            return Result.error("答案不能为空");
        }
        if (item.getCategory() == null || item.getCategory().trim().isEmpty()) {
            return Result.error("分类不能为空");
        }
        if (item.getTags() == null || item.getTags().isEmpty()) {
            return Result.error("标签不能为空");
        }
        try {
            KnowledgeItem saved = knowledgeService.save(item);
            return Result.success(saved);
        } catch (Exception e) {
            return Result.error("创建失败: " + e.getMessage());
        }
    }

    @PostMapping(value = "/knowledge/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeItem> importDocument(@RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "category", required = false) String category,
                                                @RequestParam(value = "tags", required = false) String tags,
                                                @RequestParam(value = "priority", required = false) Integer priority,
                                                @RequestParam(value = "enabled", required = false) Boolean enabled) {
        if (file == null || file.isEmpty()) {
            return Result.badRequest("上传文件不能为空");
        }
        try {
            String originalName = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();
            String extension = getExtension(originalName);
            if (!List.of("txt", "pdf").contains(extension)) {
                return Result.badRequest("仅支持 txt 或 pdf 文档导入");
            }

            String rawText = "pdf".equals(extension) ? extractPdfText(file) : extractTxtText(file);
            String normalizedText = normalizeImportedText(rawText);
            if (normalizedText.isBlank()) {
                return Result.badRequest("文档内容为空，无法导入");
            }

            KnowledgeItem item = KnowledgeItem.builder()
                    .question(stripExtension(originalName))
                    .answer(normalizedText)
                    .category((category == null || category.isBlank()) ? "知识文档" : category.trim())
                    .tags((tags == null || tags.isBlank()) ? "文档导入," + extension : tags.trim())
                    .priority(priority == null ? 6 : priority)
                    .enabled(enabled == null ? Boolean.TRUE : enabled)
                    .build();
            KnowledgeItem saved = knowledgeService.save(item);
            return Result.success(saved);
        } catch (Exception e) {
            log.error("导入知识库文档失败", e);
            return Result.error("导入失败: " + e.getMessage());
        }
    }

    @PutMapping("/knowledge/{id}")
    public Result<KnowledgeItem> update(@PathVariable Long id, @RequestBody KnowledgeItem item) {
        try {
            KnowledgeItem updated = knowledgeService.update(id, item);
            return Result.success(updated);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("更新失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/knowledge/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            knowledgeService.delete(id);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("删除失败: " + e.getMessage());
        }
    }

    @GetMapping("/simulate/scripts")
    public Result<List<Map<String, Object>>> getSimulateScripts() {
        List<Map<String, Object>> scripts = List.of(
                toScriptMap(ScamScenario.IMPERSONATE_RELATIVE, "relative",
                        "#ef4444", "高难度", "诈骗者冒充您的亲友，以紧急情况为由请求汇款"),
                toScriptMap(ScamScenario.IMPERSONATE_POLICE, "police",
                        "#f59e0b", "高难度", "诈骗者冒充警察/检察官，声称您涉嫌犯罪要求转账到安全账户"),
                toScriptMap(ScamScenario.INVESTMENT, "investment",
                        "#10b981", "中等难度", "以高收益投资机会为诱饵，诱导您投入资金"),
                toScriptMap(ScamScenario.CUSTOMER_SERVICE, "online",
                        "#3b82f6", "入门难度", "冒充电商客服，以退款、补偿为由骗取信息或钱财")
        );
        return Result.success(scripts);
    }

    @GetMapping("/simulate/start/{scriptId}")
    public Result<SimulationResponse> startSimulation(@PathVariable String scriptId) {
        ScamScenario scenario = ScamScenario.resolve(scriptId);
        SimulationResponse response = new SimulationResponse();
        response.setContent(getInitialMessage(scenario));
        response.setQuickReplies(getInitialQuickReplies(scenario));
        response.setSuspicionDelta(0);
        response.setFinished(false);
        return Result.success(response);
    }

    @PostMapping("/simulate/chat")
    public Result<SimulationResponse> simulateChat(@RequestBody ChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return Result.error("消息不能为空");
        }
        String validationError = InputValidator.validateMessage(request.getMessage());
        if (validationError != null) {
            return Result.error(validationError);
        }
        ScamScenario scenario = ScamScenario.resolve(request.getScriptId());
        return Result.success(llmService.scamSimulationStructured(
                request.getMessage(),
                request.getHistory(),
                scenario
        ));
    }

    @PostMapping("/simulate/end")
    public Result<Map<String, Object>> endSimulation(@RequestBody Map<String, Object> request) {
        String scriptId = request.getOrDefault("scriptId", "relative").toString();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");

        StringBuilder context = new StringBuilder();
        context.append("【诈骗剧本】").append(scriptId).append("\n");
        if (messages != null) {
            context.append("【对话历史】\n");
            for (Map<String, Object> msg : messages) {
                Object role = msg.get("role");
                Object content = msg.get("content");
                if (role != null && content != null) {
                    context.append(role).append("：").append(content).append("\n");
                }
            }
        }
        context.append("\n请分析以上对话中我（user）是否识别出了诈骗迹象，指出我的警觉表现和不足之处，并给出防骗建议。");

        String result = llmService.analyzeText(context.toString());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("analysis", result);
        response.put("tips", extractTipsFromAnalysis(result));
        return Result.success(response);
    }

    private Map<String, Object> toScriptMap(ScamScenario scenario, String id,
                                             String color, String difficulty, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", scenario.getName());
        map.put("color", color);
        map.put("difficulty", difficulty);
        map.put("description", description);
        map.put("systemPrompt", scenario.getSystemPrompt());
        return map;
    }

    private String getInitialMessage(ScamScenario scenario) {
        return switch (scenario) {
            case IMPERSONATE_POLICE -> "你好，这里是市公安反诈专案组。你的身份证信息涉及一宗洗钱案，现在需要你配合做线上核验，先不要告诉任何人。";
            case CUSTOMER_SERVICE -> "您好，我是平台客服。您之前的一笔订单被系统误开了自动扣费服务，如果不取消，今晚会从银行卡扣款。";
            case INVESTMENT -> "我这边是内部投研群的助理，今天有一只短线标的，老师说新用户可以跟一单小额试试，收益很稳。";
            case BRUSH_ORDER -> "您好，看到你报名了商家热度任务。第一单很简单，垫付后3分钟返本金和佣金，可以先做个小额体验。";
            default -> "在吗？我是你表哥，手机摔坏了，微信刚登上来。现在有点急事，方便先帮我处理一下吗？";
        };
    }

    private List<String> getInitialQuickReplies(ScamScenario scenario) {
        return switch (scenario) {
            case IMPERSONATE_POLICE -> List.of("你是哪个单位？", "我需要打110核实", "什么案件？");
            case CUSTOMER_SERVICE -> List.of("我去官方App看", "为什么会扣款？", "需要我怎么操作？");
            case INVESTMENT -> List.of("收益有多少？", "我不投陌生平台", "先发资料看看");
            case BRUSH_ORDER -> List.of("要先垫钱吗？", "佣金怎么算？", "我不做刷单");
            default -> List.of("你真是我表哥？", "先打个电话", "要我帮什么？");
        };
    }

    private List<String> extractTipsFromAnalysis(String analysis) {
        List<String> tips = new ArrayList<>();
        if (analysis == null || analysis.isBlank()) {
            tips.addAll(getDefaultTips());
            return tips;
        }
        String[] lines = analysis.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d+[\\.,、）\\)]\\s*.*") && trimmed.length() > 5) {
                tips.add(trimmed.replaceFirst("^\\d+[\\.,、）\\)]\\s*", ""));
            }
        }
        if (tips.isEmpty()) {
            tips.addAll(getDefaultTips());
        }
        return tips;
    }

    private List<String> getDefaultTips() {
        return List.of(
                "公检法机关不会通过电话办案，不会要求转账到安全账户",
                "任何自称熟人的陌生来电都应通过原有联系方式核实",
                "96110 是全国反诈中心统一预警劝阻咨询电话"
        );
    }

    private String extractTxtText(MultipartFile file) throws IOException {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private String extractPdfText(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String normalizeImportedText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (normalized.length() > MAX_IMPORT_TEXT_LENGTH) {
            return normalized.substring(0, MAX_IMPORT_TEXT_LENGTH) + "\n\n[内容过长，已截断用于演示导入]";
        }
        return normalized;
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }
}
