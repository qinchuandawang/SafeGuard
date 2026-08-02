package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.service.ArchiveService;
import com.sdu.safeguard.service.InferenceCapacityService;
import com.sdu.safeguard.service.TokenCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/reliability")
@RequiredArgsConstructor
public class AdminReliabilityController {

    private final ArchiveService archiveService;
    private final InferenceCapacityService inferenceCapacityService;
    private final TokenCostService tokenCostService;

    @GetMapping("/inference-capacity")
    public Result<List<Map<String, Object>>> inferenceCapacity() {
        return Result.success(inferenceCapacityService.snapshots());
    }

    @GetMapping("/llm-token-budget")
    public Result<Map<String, Object>> llmTokenBudget() {
        return Result.success(tokenCostService.snapshot());
    }

    @PostMapping("/archives/run")
    public Result<Map<String, Object>> runArchive() {
        return Result.success(archiveService.runOneBatch());
    }

    @GetMapping("/archives/stats")
    public Result<Map<String, Object>> archiveStats() {
        return Result.success(archiveService.stats());
    }

}
