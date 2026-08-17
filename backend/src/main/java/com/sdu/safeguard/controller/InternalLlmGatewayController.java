package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.dto.TextDetectionResult;
import com.sdu.safeguard.service.LLMService;
import com.sdu.safeguard.util.InputValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** LangGraph 等内部调用方访问统一 LLM Gateway 的入口。 */
@RestController
@RequestMapping("/api/internal/llm")
@RequiredArgsConstructor
public class InternalLlmGatewayController {

    private final LLMService llmService;

    @Value("${ai.orchestrator.internal-token:}")
    private String internalToken;

    @PostMapping("/text-detection")
    public Result<TextDetectionResult> detectText(
            @RequestBody TextDetectionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        verifyInternalToken(authorization);
        String text = request == null ? null : request.text();
        String validationError = InputValidator.validateAnalysisText(text);
        if (validationError != null) {
            return Result.badRequest(validationError);
        }
        return Result.success(llmService.analyzeTextStructured(text));
    }

    private void verifyInternalToken(String authorization) {
        if (internalToken == null || internalToken.isBlank()) {
            return;
        }
        String expected = "Bearer " + internalToken;
        byte[] actualBytes = (authorization == null ? "" : authorization)
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actualBytes)) {
            throw new ResponseStatusException(UNAUTHORIZED, "内部服务凭证无效");
        }
    }

    public record TextDetectionRequest(String text, String taskId, String traceId) {
    }
}
