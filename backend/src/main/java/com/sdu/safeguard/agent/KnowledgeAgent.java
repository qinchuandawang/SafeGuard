package com.sdu.safeguard.agent;

import com.sdu.safeguard.dto.AgentRequest;
import com.sdu.safeguard.dto.AgentResponse;
import com.sdu.safeguard.dto.RagQueryResult;
import com.sdu.safeguard.rag.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeAgent implements Agent {

    private final RAGService ragService;

    @Override
    public String getType() {
        return "KNOWLEDGE";
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        try {
            @SuppressWarnings("unchecked")
            List<RagQueryResult> results;
            boolean reused = false;
            if (request.getContext() != null) {
                Object ctx = request.getContext().get("ragResults");
                if (ctx instanceof List<?>) {
                    results = (List<RagQueryResult>) ctx;
                    reused = true;
                    log.debug("KnowledgeAgent复用编排层RAG结果: {} 条", results.size());
                } else {
                    results = ragService.query(request.getInput());
                }
            } else {
                results = ragService.query(request.getInput());
            }
            String context = ragService.formatRagContext(results);

            double avgScore = results.isEmpty() ? 0.0 :
                    results.stream().mapToDouble(RagQueryResult::getScore).average().orElse(0.0);

            return AgentResponse.builder()
                    .agentType("KNOWLEDGE")
                    .result(context)
                    .confidence(avgScore)
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
}