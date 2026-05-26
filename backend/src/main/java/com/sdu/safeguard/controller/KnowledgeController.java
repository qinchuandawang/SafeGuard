package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.ChatRequest;
import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.KnowledgeItem;
import com.sdu.safeguard.dto.ScamScenario;
import com.sdu.safeguard.service.KnowledgeService;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.util.InputValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final LLMService llmService;

    @GetMapping("/knowledge/search")
    public Result<List<KnowledgeItem>> search(@RequestParam("keyword") String keyword) {
        String validationError = InputValidator.validateKeyword(keyword);
        if (validationError != null) {
            return Result.error(validationError);
        }
        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.error("关键词不能为空");
        }
        List<KnowledgeItem> result = knowledgeService.search(keyword.trim());
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
    public Result<String> startSimulation(@PathVariable String scriptId) {
        ScamScenario scenario = ScamScenario.resolve(scriptId);
        String initMessage = "你好，我是" + scenario.getName() + "的演练对象。\n\n" + scenario.getSystemPrompt();
        return Result.success(initMessage);
    }

    @PostMapping("/simulate/chat")
    public Result<String> simulateChat(@RequestBody ChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return Result.error("消息不能为空");
        }
        String validationError = InputValidator.validateMessage(request.getMessage());
        if (validationError != null) {
            return Result.error(validationError);
        }
        ScamScenario scenario = ScamScenario.resolve(request.getScriptId());
        return Result.success(llmService.scamSimulation(
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
}