package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.*;
import com.sdu.safeguard.rag.RAGService;
import com.sdu.safeguard.reasoning.CoTService;
import com.sdu.safeguard.reasoning.ReActService;
import com.sdu.safeguard.util.InputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RAGController {

    private final RAGService ragService;
    private final CoTService cotService;
    private final ReActService reActService;

    @GetMapping("/rag/query")
    public Result<List<RagQueryResult>> queryRAG(@RequestParam("q") String query) {
        String validationError = InputValidator.validateAnalysisText(query);
        if (validationError != null) {
            return Result.error(validationError);
        }
        try {
            List<RagQueryResult> results = ragService.query(query.trim());
            return Result.success(results);
        } catch (Exception e) {
            log.error("RAG查询异常", e);
            return Result.error("知识检索失败：" + e.getMessage());
        }
    }

    @GetMapping("/rag/stats")
    public Result<Map<String, Object>> getRAGStats() {
        try {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("totalChunks", ragService.getTotalChunks());
            stats.put("topics", ragService.getTopics());
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error("获取状态失败");
        }
    }

    @PostMapping("/rag/reload")
    public Result<String> reloadKnowledge() {
        try {
            ragService.reloadKnowledge();
            return Result.success("知识库重新加载完成");
        } catch (Exception e) {
            log.error("知识库重载失败", e);
            return Result.error("重载失败：" + e.getMessage());
        }
    }

    @PostMapping("/analyze/cot")
    public Result<CoTResult> analyzeWithCoT(@RequestBody Map<String, String> request) {
        if (request == null || !request.containsKey("text")) {
            return Result.error("文本不能为空");
        }
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            return Result.error("文本不能为空");
        }
        try {
            CoTResult result = cotService.analyzeWithCoT(text);
            return Result.success(result);
        } catch (Exception e) {
            log.error("CoT分析异常", e);
            return Result.error("分析失败：" + e.getMessage());
        }
    }

    @PostMapping("/analyze/react")
    public Result<List<ReActThought>> analyzeWithReAct(@RequestBody Map<String, String> request) {
        if (request == null || !request.containsKey("text")) {
            return Result.error("文本不能为空");
        }
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            return Result.error("文本不能为空");
        }
        String sessionId = request.getOrDefault("sessionId", UUID.randomUUID().toString());
        try {
            List<ReActThought> thoughts = reActService.executeReAct(text, sessionId);
            return Result.success(thoughts);
        } catch (Exception e) {
            log.error("ReAct分析异常", e);
            return Result.error("分析失败：" + e.getMessage());
        }
    }
}
