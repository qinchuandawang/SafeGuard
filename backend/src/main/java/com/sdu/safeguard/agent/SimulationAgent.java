package com.sdu.safeguard.agent;

import com.sdu.safeguard.dto.AgentRequest;
import com.sdu.safeguard.dto.AgentResponse;
import com.sdu.safeguard.dto.ReActThought;
import com.sdu.safeguard.reasoning.ReActService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationAgent implements Agent {

    private final ReActService reActService;

    @Override
    public String getType() {
        return "SIMULATION";
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        try {
            @SuppressWarnings("unchecked")
            List<ReActThought> thoughts;
            if (request.getContext() != null) {
                Object ctx = request.getContext().get("reactThoughts");
                if (ctx instanceof List<?>) {
                    thoughts = new ArrayList<>((List<ReActThought>) ctx);
                    log.debug("SimulationAgent复用编排层ReAct结果: {} 步", thoughts.size());
                } else {
                    thoughts = reActService.executeReAct(request.getInput(),
                            request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString());
                }
            } else {
                thoughts = reActService.executeReAct(request.getInput(),
                        request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString());
            }

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
}