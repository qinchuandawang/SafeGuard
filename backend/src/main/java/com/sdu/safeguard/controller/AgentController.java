package com.sdu.safeguard.controller;

import com.sdu.safeguard.agent.AgentOrchestrator;
import com.sdu.safeguard.dto.OrchestratorRequest;
import com.sdu.safeguard.dto.OrchestratorResponse;
import com.sdu.safeguard.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentOrchestrator orchestrator;

    @PostMapping("/analyze")
    public Result<OrchestratorResponse> analyze(@RequestBody OrchestratorRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return Result.error("查询内容不能为空");
        }
        try {
            OrchestratorResponse response = orchestrator.execute(request);
            return Result.success(response);
        } catch (Exception e) {
            log.error("Agent分析异常", e);
            return Result.error("分析失败：" + e.getMessage());
        }
    }

    @PostMapping("/analyze/text")
    public Result<OrchestratorResponse> analyzeText(@RequestBody Map<String, Object> request) {
        String text = request != null ? (String) request.get("text") : null;
        if (text == null || text.isBlank()) {
            return Result.error("文本不能为空");
        }
        String sessionId = (String) request.getOrDefault("sessionId", UUID.randomUUID().toString());

        OrchestratorRequest agentRequest = OrchestratorRequest.builder()
                .query(text)
                .sessionId(sessionId)
                .mode("ANALYSIS")
                .requiredAgents(List.of("TEXT_ANALYSIS", "KNOWLEDGE"))
                .useReAct(true)
                .useCoT(true)
                .useRAG(true)
                .build();

        try {
            OrchestratorResponse response = orchestrator.execute(agentRequest);
            return Result.success(response);
        } catch (Exception e) {
            log.error("文本分析异常", e);
            return Result.error("分析失败：" + e.getMessage());
        }
    }

    @PostMapping("/simulate")
    public Result<OrchestratorResponse> simulate(@RequestBody Map<String, Object> request) {
        String text = request != null ? (String) request.get("text") : null;
        if (text == null || text.isBlank()) {
            return Result.error("输入不能为空");
        }
        String sessionId = (String) request.getOrDefault("sessionId", UUID.randomUUID().toString());

        OrchestratorRequest agentRequest = OrchestratorRequest.builder()
                .query(text)
                .sessionId(sessionId)
                .mode("SIMULATION")
                .requiredAgents(List.of("SIMULATION", "KNOWLEDGE"))
                .useReAct(true)
                .useCoT(false)
                .useRAG(true)
                .build();

        try {
            OrchestratorResponse response = orchestrator.execute(agentRequest);
            return Result.success(response);
        } catch (Exception e) {
            log.error("模拟分析异常", e);
            return Result.error("分析失败：" + e.getMessage());
        }
    }
}
