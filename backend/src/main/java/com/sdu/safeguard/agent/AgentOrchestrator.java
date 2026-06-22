package com.sdu.safeguard.agent;

import com.sdu.safeguard.dto.*;
import com.sdu.safeguard.memory.MemoryService;
import com.sdu.safeguard.rag.RAGService;
import com.sdu.safeguard.reasoning.CoTService;
import com.sdu.safeguard.reasoning.ReActService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class AgentOrchestrator {

    private final RAGService ragService;
    private final CoTService cotService;
    private final ReActService reActService;
    private final MemoryService memoryService;
    private final Executor agentExecutor;
    private final AgentRegistry agentRegistry;

    public AgentOrchestrator(RAGService ragService, CoTService cotService,
                             ReActService reActService, MemoryService memoryService,
                             @Qualifier("detectionTaskExecutor") Executor agentExecutor,
                             AgentRegistry agentRegistry) {
        this.ragService = ragService;
        this.cotService = cotService;
        this.reActService = reActService;
        this.memoryService = memoryService;
        this.agentExecutor = agentExecutor;
        this.agentRegistry = agentRegistry;
    }

    public OrchestratorResponse execute(OrchestratorRequest request) {
        long startTime = System.currentTimeMillis();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();

        log.info("Orchestrator开始执行: mode={}, agents={}", request.getMode(), request.getRequiredAgents());

        memoryService.addShortTerm(sessionId, "user", request.getQuery(), List.of("query"));

        final long TOTAL_TIMEOUT_MS = 120_000;
        long remaining = TOTAL_TIMEOUT_MS;

        // 阶段A: 依赖解析 — RAG、CoT、ReAct 并行执行
        CompletableFuture<List<RagQueryResult>> ragFuture = request.isUseRAG()
                ? CompletableFuture.supplyAsync(() -> ragService.query(request.getQuery()), agentExecutor)
                : CompletableFuture.completedFuture(new ArrayList<>());

        CompletableFuture<CoTResult> cotFuture = request.isUseCoT()
                ? ragFuture.thenApplyAsync(ragCtx -> {
                    String knowledge = ragService.formatRagContext(ragCtx);
                    return cotService.analyzeWithCoT(request.getQuery(), knowledge);
                }, agentExecutor)
                : CompletableFuture.completedFuture(CoTResult.builder().reasoningSteps(List.of()).build());

        CompletableFuture<List<ReActThought>> reactFuture = request.isUseReAct()
                ? ragFuture.thenApplyAsync(ragCtx -> {
                    String knowledge = ragService.formatRagContext(ragCtx);
                    return reActService.executeReAct(request.getQuery(), sessionId, knowledge, request.getFileType());
                }, agentExecutor)
                : CompletableFuture.completedFuture(new ArrayList<>());

        try {
            CompletableFuture.allOf(ragFuture, cotFuture, reactFuture).get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("依赖解析超时 ({}ms)", remaining);
        } catch (Exception e) {
            log.error("依赖解析失败", e);
        }

        List<RagQueryResult> ragContext = ragFuture.getNow(new ArrayList<>());
        CoTResult cotResult = cotFuture.getNow(CoTResult.builder().reasoningSteps(List.of()).build());
        List<ReActThought> reactThoughts = reactFuture.getNow(new ArrayList<>());

        // 计算阶段B剩余时间
        remaining = Math.max(1, TOTAL_TIMEOUT_MS - (System.currentTimeMillis() - startTime));

        // 阶段B: Agent 并行执行
        Map<String, Future<AgentResponse>> futures = new HashMap<>();
        List<String> agents = request.getRequiredAgents() != null && !request.getRequiredAgents().isEmpty()
                ? request.getRequiredAgents()
                : List.of("TEXT_ANALYSIS", "KNOWLEDGE");

        for (String agentType : agents) {
            AgentRequest agentRequest = buildAgentRequest(agentType, request, ragContext, cotResult, reactThoughts);
            futures.put(agentType,
                    CompletableFuture.supplyAsync(() -> executeAgent(agentRequest), agentExecutor));
        }

        Map<String, AgentResponse> agentResults = new LinkedHashMap<>();
        for (Map.Entry<String, Future<AgentResponse>> entry : futures.entrySet()) {
            try {
                AgentResponse response = entry.getValue().get(remaining, TimeUnit.MILLISECONDS);
                agentResults.put(entry.getKey(), response);
            } catch (TimeoutException e) {
                log.warn("Agent超时 (剩余{}ms): {}", remaining, entry.getKey());
                agentResults.put(entry.getKey(), AgentResponse.builder()
                        .agentType(entry.getKey()).status("FAILED").error("执行超时").build());
            } catch (Exception e) {
                log.error("Agent执行失败: {}", entry.getKey(), e);
                agentResults.put(entry.getKey(), AgentResponse.builder()
                        .agentType(entry.getKey()).status("FAILED").error(e.getMessage()).build());
            }
        }

        // 阶段C: 聚合输出
        String finalResult = mergeResults(agentResults, cotResult, reactThoughts, ragContext);
        List<String> reasoningSummary = buildReasoningSummary(cotResult, reactThoughts, agentResults);

        memoryService.addShortTerm(sessionId, "assistant",
                finalResult.substring(0, Math.min(200, finalResult.length())),
                List.of("response", request.getMode() != null ? request.getMode() : "unknown"));

        long cost = System.currentTimeMillis() - startTime;
        log.info("Orchestrator执行完成: mode={}, cost={}ms", request.getMode(), cost);

        return OrchestratorResponse.builder()
                .finalResult(finalResult)
                .agentResults(agentResults)
                .reactThoughts(reactThoughts)
                .cotResult(cotResult)
                .ragContext(ragContext)
                .reasoningSummary(reasoningSummary)
                .processingTimeMs(cost)
                .build();
    }

    private AgentRequest buildAgentRequest(String agentType, OrchestratorRequest request,
                                           List<RagQueryResult> ragContext, CoTResult cotResult,
                                           List<ReActThought> reactThoughts) {
        Map<String, Object> context = new HashMap<>();
        context.put("ragResults", ragContext);
        context.put("cotResult", cotResult);
        context.put("reactThoughts", reactThoughts);
        context.put("sessionId", request.getSessionId());
        if (request.getFilePath() != null) {
            context.put("filePath", request.getFilePath());
        }

        return AgentRequest.builder()
                .agentType(agentType)
                .input(request.getQuery())
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .context(context)
                .filePath(request.getFilePath())
                .fileType(request.getFileType())
                .parameters(Map.of("mode", request.getMode() != null ? request.getMode() : "auto"))
                .build();
    }

    private AgentResponse executeAgent(AgentRequest request) {
        Agent agent = agentRegistry.getAgent(request.getAgentType());
        if (agent != null) {
            return agent.execute(request);
        }
        return AgentResponse.builder()
                .agentType(request.getAgentType())
                .status("FAILED")
                .error("未知Agent类型: " + request.getAgentType())
                .build();
    }

    private String mergeResults(Map<String, AgentResponse> agentResults,
                                 CoTResult cotResult,
                                 List<ReActThought> reactThoughts,
                                 List<RagQueryResult> ragContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 综合检测分析报告\n\n");

        if (!ragContext.isEmpty()) {
            sb.append("### 参考知识\n");
            for (RagQueryResult r : ragContext) {
                sb.append("- ").append(r.getCategory()).append(": ")
                        .append(r.getContent().substring(0, Math.min(80, r.getContent().length())))
                        .append("...\n");
            }
            sb.append("\n");
        }

        if (cotResult != null && cotResult.getReasoningSteps() != null && !cotResult.getReasoningSteps().isEmpty()) {
            sb.append("### 思维链分析\n");
            for (String step : cotResult.getReasoningSteps()) {
                sb.append("- ").append(step).append("\n");
            }
            sb.append("\n**风险评估**: ").append(cotResult.getRiskLevel())
                    .append(" (概率: ").append(String.format("%.1f%%", cotResult.getRiskProbability() * 100))
                    .append(")\n\n");
            if (!cotResult.getAdvice().isEmpty()) {
                sb.append("**防范建议**: ").append(cotResult.getAdvice()).append("\n\n");
            }
        }

        for (Map.Entry<String, AgentResponse> entry : agentResults.entrySet()) {
            AgentResponse resp = entry.getValue();
            if ("SUCCESS".equals(resp.getStatus())) {
                sb.append("### ").append(resp.getAgentType()).append("\n");
                sb.append(resp.getResult()).append("\n");
                if (resp.getData() != null && !resp.getData().isEmpty()) {
                    sb.append("检测详情: ").append(resp.getData()).append("\n");
                }
                sb.append("\n");
            }
        }

        if (reactThoughts != null && !reactThoughts.isEmpty()) {
            sb.append("### ReAct推理过程\n");
            for (ReActThought t : reactThoughts) {
                sb.append("Step ").append(t.getStep()).append(":\n");
                sb.append("- 思考: ").append(t.getThought()).append("\n");
                sb.append("- 行动: ").append(t.getAction()).append("\n");
                sb.append("- 观察: ").append(truncate(t.getObservation(), 150)).append("\n\n");
            }
        }

        return sb.toString();
    }

    private List<String> buildReasoningSummary(CoTResult cotResult,
                                                List<ReActThought> reactThoughts,
                                                Map<String, AgentResponse> agentResults) {
        List<String> summary = new ArrayList<>();
        if (cotResult != null && cotResult.getRiskLevel() != null) {
            summary.add("CoT: " + cotResult.getRiskLevel() + "风险");
        }
        if (reactThoughts != null && !reactThoughts.isEmpty()) {
            summary.add("ReAct: " + reactThoughts.size() + "步推理");
        }
        for (Map.Entry<String, AgentResponse> e : agentResults.entrySet()) {
            summary.add(e.getKey() + ": " + e.getValue().getStatus());
        }
        return summary;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
