package com.sdu.safeguard.service;

import com.sdu.safeguard.dto.VideoDetectionResult;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class VideoMetadataEvidenceService {

    public void applyMetadataEvidence(VideoDetectionResult result, File videoFile) {
        if (result == null || videoFile == null || !videoFile.isFile()) {
            return;
        }

        Map<String, String> metadata = readMetadata(videoFile);
        Map<String, String> evidence = pickEvidence(metadata);
        if (evidence.isEmpty()) {
            result.setAigcMetadataDetected(false);
            return;
        }

        List<String> reasons = buildReasons(evidence);
        boolean strongAigcEvidence = hasStrongAigcEvidence(evidence);
        result.setAigcMetadataDetected(strongAigcEvidence);
        result.setMetadataEvidence(evidence);
        result.setEvidenceReasons(reasons);

        if (strongAigcEvidence) {
            double boostedProbability = Math.max(valueOrZero(result.getFakeProbability()), 0.95);
            result.setFakeProbability(boostedProbability);
            result.setConfidence(Math.max(valueOrZero(result.getConfidence()), 0.95));
            result.setDetermination("fake");
            result.setFakeType("aigc_metadata");
            result.computeProbabilities();
            log.info("视频元数据命中 AIGC 强证据，已提升风险: file={}, evidenceKeys={}",
                    videoFile.getName(), evidence.keySet());
        }
    }

    private Map<String, String> readMetadata(File videoFile) {
        Map<String, String> metadata = new LinkedHashMap<>();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            grabber.start();
            putAll(metadata, grabber.getMetadata());
            putAll(metadata, grabber.getVideoMetadata());
            putAll(metadata, grabber.getAudioMetadata());
            grabber.stop();
        } catch (Exception e) {
            log.warn("读取视频元数据失败，继续使用视觉模型结果: {}", e.getMessage());
        }
        return metadata;
    }

    private void putAll(Map<String, String> target, Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach((key, value) -> {
            if (key != null && value != null && !value.isBlank()) {
                target.put(key, value);
            }
        });
    }

    private Map<String, String> pickEvidence(Map<String, String> metadata) {
        Map<String, String> evidence = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            String haystack = (key + " " + value).toLowerCase(Locale.ROOT);
            if (haystack.contains("aigc")
                    || haystack.contains("contentproducer")
                    || haystack.contains("produceid")
                    || haystack.contains("doubao")
                    || haystack.contains("ai视频生成")
                    || haystack.contains("ai video")) {
                evidence.put(key, value);
            }
        });
        return evidence;
    }

    private boolean hasStrongAigcEvidence(Map<String, String> evidence) {
        return evidence.entrySet().stream().anyMatch(entry -> {
            String haystack = (entry.getKey() + " " + entry.getValue()).toLowerCase(Locale.ROOT);
            return haystack.contains("aigc")
                    || haystack.contains("\"label\": \"1\"")
                    || haystack.contains("\"label\":\"1\"")
                    || haystack.contains("contentproducer")
                    || haystack.contains("produceid")
                    || haystack.contains("doubao");
        });
    }

    private List<String> buildReasons(Map<String, String> evidence) {
        List<String> reasons = new ArrayList<>();
        evidence.forEach((key, value) -> {
            String lower = (key + " " + value).toLowerCase(Locale.ROOT);
            if (lower.contains("aigc")) {
                reasons.add("视频文件元数据包含 AIGC 标记。");
            } else if (lower.contains("doubao")) {
                reasons.add("视频文件元数据显示来源或生成产品与豆包相关。");
            } else if (lower.contains("contentproducer") || lower.contains("produceid")) {
                reasons.add("视频文件元数据包含内容生产者或生成 ID 字段。");
            }
        });
        if (reasons.isEmpty()) {
            reasons.add("视频文件元数据包含疑似生成式内容来源字段。");
        }
        return reasons.stream().distinct().toList();
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : Math.max(0.0, Math.min(1.0, value));
    }
}
