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

    public AgentOrchestrator(RAGService ragService, CoTService cotService,
                             ReActService reActService, MemoryService memoryService,
                             @Qualifier("detectionTaskExecutor") Executor agentExecutor) {
        this.ragService = ragService;
        this.cotService = cotService;
        this.reActService = reActService;
        this.memoryService = memoryService;
        this.agentExecutor = agentExecutor;
    }

    public OrchestratorResponse execute(OrchestratorRequest request) {
        long startTime = System.currentTimeMillis();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();

        log.info("Orchestrator开始执行: mode={}, agents={}, useReAct={}, useCoT={}, useRAG={}",
                request.getMode(), request.getRequiredAgents(),
                request.isUseReAct(), request.isUseCoT(), request.isUseRAG());

        memoryService.addShortTerm(sessionId, "user", request.getQuery(), List.of("query"));

        List<RagQueryResult> ragContext = new ArrayList<>();
        if (request.isUseRAG()) {
            ragContext = ragService.query(request.getQuery());
        }

        CoTResult cotResult = null;
        if (request.isUseCoT()) {
            cotResult = cotService.analyzeWithCoT(request.getQuery());
        }

        List<ReActThought> reactThoughts = new ArrayList<>();
        if (request.isUseReAct()) {
            reactThoughts = reActService.executeReAct(request.getQuery(), sessionId);
        }

        Map<String, Future<AgentResponse>> futures = new HashMap<>();
        List<String> agents = request.getRequiredAgents() != null && !request.getRequiredAgents().isEmpty()
                ? request.getRequiredAgents()
                : List.of("TEXT_ANALYSIS", "KNOWLEDGE");

        for (String agentType : agents) {
            AgentRequest agentRequest = buildAgentRequest(agentType, request, ragContext, cotResult);
            futures.put(agentType, CompletableFuture.supplyAsync(() -> executeAgent(agentRequest), agentExecutor));
        }

        Map<String, AgentResponse> agentResults = new LinkedHashMap<>();
        for (Map.Entry<String, Future<AgentResponse>> entry : futures.entrySet()) {
            try {
                AgentResponse response = entry.getValue().get(30, TimeUnit.SECONDS);
                agentResults.put(entry.getKey(), response);
            } catch (TimeoutException e) {
                log.warn("Agent超时: {}", entry.getKey());
                agentResults.put(entry.getKey(), AgentResponse.builder()
                        .agentType(entry.getKey())
                        .status("FAILED")
                        .error("执行超时")
                        .build());
            } catch (Exception e) {
                log.error("Agent执行失败: {}", entry.getKey(), e);
                agentResults.put(entry.getKey(), AgentResponse.builder()
                        .agentType(entry.getKey())
                        .status("FAILED")
                        .error(e.getMessage())
                        .build());
            }
        }

        String finalResult = mergeResults(request, agentResults, cotResult, reactThoughts, ragContext);

        List<String> reasoningSummary = buildReasoningSummary(cotResult, reactThoughts, agentResults);

        memoryService.addShortTerm(sessionId, "assistant",
                finalResult.substring(0, Math.min(200, finalResult.length())),
                List.of("response", request.getMode()));

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
                                            List<RagQueryResult> ragContext, CoTResult cotResult) {
        Map<String, Object> context = new HashMap<>();
        context.put("ragResults", ragContext);
        context.put("cotResult", cotResult);
        context.put("sessionId", request.getSessionId());

        return AgentRequest.builder()
                .agentType(agentType)
                .input(request.getQuery())
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .context(context)
                .parameters(Map.of("mode", request.getMode() != null ? request.getMode() : "auto"))
                .build();
    }

    private AgentResponse executeAgent(AgentRequest request) {
        return switch (request.getAgentType()) {
            case "TEXT_ANALYSIS" -> executeTextAnalysisAgent(request);
            case "KNOWLEDGE" -> executeKnowledgeAgent(request);
            case "SIMULATION" -> executeSimulationAgent(request);
            case "AUDIO_DETECTION" -> executeAudioDetectionAgent(request);
            case "VIDEO_DETECTION" -> executeVideoDetectionAgent(request);
            default -> AgentResponse.builder()
                    .agentType(request.getAgentType())
                    .status("FAILED")
                    .error("未知Agent类型: " + request.getAgentType())
                    .build();
        };
    }

    private AgentResponse executeTextAnalysisAgent(AgentRequest request) {
        try {
            CoTResult cotResult = cotService.analyzeWithCoT(request.getInput());

            String suspiciousPointsJson = "[]";
            if (cotResult.getSuspiciousPoints() != null) {
                suspiciousPointsJson = "[\"" + cotResult.getSuspiciousPoints().stream()
                        .map(this::escapeJson)
                        .collect(java.util.stream.Collectors.joining("\",\"")) + "\"]";
            }
            String analysisJson = String.format(
                    "{\"scamType\":\"%s\",\"riskLevel\":\"%s\",\"riskProbability\":%.2f," +
                    "\"suspiciousPoints\":%s,\"advice\":\"%s\"}",
                    escapeJson(cotResult.getScamType()),
                    escapeJson(cotResult.getRiskLevel()),
                    cotResult.getRiskProbability(),
                    suspiciousPointsJson,
                    escapeJson(cotResult.getAdvice()));

            return AgentResponse.builder()
                    .agentType("TEXT_ANALYSIS")
                    .result(analysisJson)
                    .confidence(cotResult.getRiskProbability())
                    .data(Map.of(
                            "scamType", cotResult.getScamType(),
                            "riskLevel", cotResult.getRiskLevel(),
                            "riskProbability", cotResult.getRiskProbability(),
                            "suspiciousPoints", cotResult.getSuspiciousPoints(),
                            "advice", cotResult.getAdvice(),
                            "reasoningSteps", cotResult.getReasoningSteps()
                    ))
                    .actions(List.of("ANALYZE_TEXT", "COT_REASONING", "KNOWLEDGE_RETRIEVAL"))
                    .reasoning(String.join("\n", cotResult.getReasoningSteps()))
                    .status("SUCCESS")
                    .build();

        } catch (Exception e) {
            log.error("TextAnalysisAgent执行失败", e);
            return AgentResponse.builder()
                    .agentType("TEXT_ANALYSIS")
                    .status("FAILED")
                    .error(e.getMessage())
                    .build();
        }
    }

    private AgentResponse executeKnowledgeAgent(AgentRequest request) {
        try {
            List<RagQueryResult> results = ragService.query(request.getInput());
            String context = ragService.formatRagContext(results);

            return AgentResponse.builder()
                    .agentType("KNOWLEDGE")
                    .result(context)
                    .confidence(results.isEmpty() ? 0.0 :
                            results.stream().mapToDouble(RagQueryResult::getScore).average().orElse(0.0))
                    .data(Map.of(
                            "resultCount", results.size(),
                            "categories", results.stream().map(RagQueryResult::getCategory).distinct().toList()
                    ))
                    .referencedKnowledge(results)
                    .actions(List.of("HYBRID_CHUNK", "BGE_EMBEDDING", "HNSW_SEARCH",
                            "KEYWORD_MATCH", "RE_RANK", "DEDUPLICATE"))
                    .status("SUCCESS")
                    .build();

        } catch (Exception e) {
            log.error("KnowledgeAgent执行失败", e);
            return AgentResponse.builder()
                    .agentType("KNOWLEDGE")
                    .status("FAILED")
                    .error(e.getMessage())
                    .build();
        }
    }

    private AgentResponse executeSimulationAgent(AgentRequest request) {
        try {
            List<ReActThought> thoughts = reActService.executeReAct(
                    request.getInput(), request.getSessionId());

            String finalAnswer = reActService.getFinalAnswer(thoughts);

            return AgentResponse.builder()
                    .agentType("SIMULATION")
                    .result(finalAnswer)
                    .confidence(0.7)
                    .data(Map.of(
                            "steps", thoughts.size(),
                            "thoughts", thoughts.stream().map(ReActThought::getThought).toList()
                    ))
                    .actions(List.of("REACT_THOUGHT", "REACT_ACTION", "REACT_OBSERVATION"))
                    .reasoning(thoughts.isEmpty() ? "" : thoughts.get(thoughts.size() - 1).getThought())
                    .status("SUCCESS")
                    .build();

        } catch (Exception e) {
            log.error("SimulationAgent执行失败", e);
            return AgentResponse.builder()
                    .agentType("SIMULATION")
                    .status("FAILED")
                    .error(e.getMessage())
                    .build();
        }
    }

    private AgentResponse executeAudioDetectionAgent(AgentRequest request) {
        return AgentResponse.builder()
                .agentType("AUDIO_DETECTION")
                .result("音频检测待接入")
                .status("PARTIAL")
                .actions(List.of("AUDIO_PREPROCESS", "WAV2VEC2_INFERENCE"))
                .build();
    }

    private AgentResponse executeVideoDetectionAgent(AgentRequest request) {
        return AgentResponse.builder()
                .agentType("VIDEO_DETECTION")
                .result("视频检测待接入")
                .status("PARTIAL")
                .actions(List.of("FRAME_EXTRACTION", "FACE_CROP", "XCEPTIONNET_INFERENCE"))
                .build();
    }

    private String mergeResults(OrchestratorRequest request,
                                 Map<String, AgentResponse> agentResults,
                                 CoTResult cotResult,
                                 List<ReActThought> reactThoughts,
                                 List<RagQueryResult> ragContext) {
        StringBuilder finalResult = new StringBuilder();
        finalResult.append("## 综合检测分析报告\n\n");

        if (!ragContext.isEmpty()) {
            finalResult.append("### 参考知识\n");
            for (RagQueryResult r : ragContext) {
                finalResult.append("- ").append(r.getCategory()).append(": ")
                        .append(r.getContent().substring(0, Math.min(80, r.getContent().length())))
                        .append("...\n");
            }
            finalResult.append("\n");
        }

        if (cotResult != null) {
            finalResult.append("### 思维链分析\n");
            for (String step : cotResult.getReasoningSteps()) {
                finalResult.append("- ").append(step).append("\n");
            }
            finalResult.append("\n**风险评估**: ").append(cotResult.getRiskLevel())
                    .append(" (概率: ").append(String.format("%.1f%%", cotResult.getRiskProbability() * 100))
                    .append(")\n\n");
            if (!cotResult.getAdvice().isEmpty()) {
                finalResult.append("**防范建议**: ").append(cotResult.getAdvice()).append("\n\n");
            }
        }

        for (Map.Entry<String, AgentResponse> entry : agentResults.entrySet()) {
            AgentResponse resp = entry.getValue();
            if ("SUCCESS".equals(resp.getStatus())) {
                finalResult.append("### ").append(resp.getAgentType()).append("\n");
                finalResult.append(resp.getResult()).append("\n\n");
            }
        }

        if (reactThoughts != null && !reactThoughts.isEmpty()) {
            finalResult.append("### ReAct推理过程\n");
            for (ReActThought t : reactThoughts) {
                finalResult.append("Step ").append(t.getStep()).append(":\n");
                finalResult.append("- 思考: ").append(t.getThought()).append("\n");
                finalResult.append("- 行动: ").append(t.getAction()).append("\n");
                finalResult.append("- 观察: ").append(truncate(t.getObservation(), 150)).append("\n\n");
            }
        }

        return finalResult.toString();
    }

    private List<String> buildReasoningSummary(CoTResult cotResult,
                                                List<ReActThought> reactThoughts,
                                                Map<String, AgentResponse> agentResults) {
        List<String> summary = new ArrayList<>();
        if (cotResult != null) {
            summary.add("CoT: " + cotResult.getRiskLevel() + "风险");
        }
        if (reactThoughts != null) {
            summary.add("ReAct: " + reactThoughts.size() + "步推理");
        }
        for (Map.Entry<String, AgentResponse> e : agentResults.entrySet()) {
            summary.add(e.getKey() + ": " + e.getValue().getStatus());
        }
        return summary;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
