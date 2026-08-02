package com.sdu.safeguard.reasoning;

import com.sdu.safeguard.agent.tool.FunctionCallingToolService;
import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.dto.ReActThought;
import com.sdu.safeguard.memory.MemoryService;
import com.sdu.safeguard.rag.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 保留 ReAct 领域入口，内部统一委托 Spring AI 原生 Tool Calling。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReActService {

    private final RAGService ragService;
    private final MemoryService memoryService;
    private final FunctionCallingToolService functionCallingToolService;

    public List<ReActThought> executeReAct(String input, String sessionId) {
        return executeReAct(input, sessionId, null, null);
    }

    public List<ReActThought> executeReAct(String input, String sessionId, String externalKnowledge) {
        return executeReAct(input, sessionId, externalKnowledge, null);
    }

    public List<ReActThought> executeReAct(String input, String sessionId,
                                           String externalKnowledge, String contextType) {
        String knowledge = externalKnowledge;
        if (knowledge == null || knowledge.isBlank()) {
            List<RagQueryResult> ragResults = ragService.query(input);
            knowledge = ragService.formatRagContext(ragResults);
        }
        String context = knowledge + "\n" + memoryService.formatMemoryContext(sessionId, input);
        List<ReActThought> thoughts = functionCallingToolService.execute(input, context, sessionId, contextType);
        if (thoughts.isEmpty()) {
            thoughts = List.of(ReActThought.builder()
                    .step(1)
                    .thought("AI 模型不可用，返回知识检索降级结果")
                    .action("FINISH")
                    .observation(knowledge)
                    .finalAnswer(knowledge)
                    .referencedSources(List.of())
                    .isFinal(true)
                    .build());
        }
        memoryService.addShortTerm(sessionId, "system", "AI分析结果: " + getFinalAnswer(thoughts),
                List.of("spring-ai", "tool-calling"));
        log.info("Spring AI Tool Calling 完成: {} steps", thoughts.size());
        return thoughts;
    }

    public String getFinalAnswer(List<ReActThought> thoughts) {
        if (thoughts == null || thoughts.isEmpty()) {
            return "";
        }
        ReActThought last = thoughts.get(thoughts.size() - 1);
        return last.getFinalAnswer() != null ? last.getFinalAnswer() : last.getObservation();
    }
}
