package com.sdu.safeguard.controller;

import com.sdu.safeguard.config.LLMConfig;
import com.sdu.safeguard.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final LLMConfig llmConfig;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString(),
                "service", "SafeGuard",
                "llmConfigured", llmConfig.getApiKey() != null && !llmConfig.getApiKey().isBlank(),
                "llmModel", llmConfig.getModel() == null ? "" : llmConfig.getModel(),
                "llmApiUrlConfigured", llmConfig.getApiUrl() != null && !llmConfig.getApiUrl().isBlank()
        ));
    }
}
