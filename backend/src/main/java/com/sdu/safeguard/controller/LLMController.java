package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.ChatRequest;
import com.sdu.safeguard.dto.ScamScenario;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.util.InputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LLMController {

    private final LLMService llmService;

    @PostMapping(value = "/scam/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scamChatStream(@RequestBody ChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }
        ScamScenario scenario = ScamScenario.resolve(request.getScriptId());
        return llmService.streamScamSimulation(
                request.getMessage(),
                request.getHistory(),
                scenario
        );
    }

    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@RequestBody Map<String, String> request) {
        if (request == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }
        String text = request.get("text");
        if (text == null || text.trim().isEmpty()) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }
        return llmService.streamCallLLM(llmService.buildAnalyzePrompt(text));
    }
}
