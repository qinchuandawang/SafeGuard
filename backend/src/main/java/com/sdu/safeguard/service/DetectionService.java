package com.sdu.safeguard.service;

import com.sdu.safeguard.config.VideoProperties;
import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.dto.VideoDetectionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Map;
import java.util.function.BiConsumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionService {

    private final RestTemplate restTemplate;
    @Autowired
    @Qualifier("videoRestTemplate")
    private RestTemplate videoRestTemplate;
    private final VideoImagePipelineService videoImagePipelineService;
    private final VideoProperties videoProperties;
    private final VideoMetadataEvidenceService videoMetadataEvidenceService;

    @Value("${audio.service.url:http://localhost:5000/audio/detect}")
    private String audioServiceUrl;

    @Value("${video.service.url:http://localhost:5002/api/detect/video}")
    private String videoServiceUrl;

    public AudioDetectionResult detectAudio(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("音频文件不存在，请确认文件已正确上传: " + filePath);
        }
        AudioDetectionResult result = callAudioDetection(filePath, audioServiceUrl);
        if (result != null) {
            result.computeProbabilities();
        }
        return result;
    }

    /**
     * 通过 MultipartFile 直接上传到音频检测服务（跨容器安全）
     */
    public AudioDetectionResult detectAudioWithMultipart(MultipartFile multipartFile) {
        AudioDetectionResult result = callAudioDetectionWithMultipart(multipartFile, audioServiceUrl);
        if (result != null) {
            result.computeProbabilities();
        }
        return result;
    }

    public VideoDetectionResult detectVideo(String filePath) {
        return detectVideo(filePath, null);
    }

    public VideoDetectionResult detectVideo(String filePath,
                                            BiConsumer<Integer, Map<String, Object>> progressCallback) {
        VideoDetectionResult result;
        if (shouldUseVideoPreprocess()) {
            result = videoImagePipelineService.detectFromVideoFile(filePath, progressCallback);
        } else {
            if (progressCallback != null) {
                progressCallback.accept(10, Map.of("message", "正在向 AI 检测服务提交视频..."));
            }
            result = callVideoDetection(filePath, videoServiceUrl);
            if (progressCallback != null && result != null) {
                progressCallback.accept(80, Map.of("message", "AI 检测完成，正在汇总结果..."));
            }
        }
        if (result != null) {
            videoMetadataEvidenceService.applyMetadataEvidence(result, new File(filePath));
            result.computeProbabilities();
        }
        return result;
    }

    private boolean shouldUseVideoPreprocess() {
        return videoProperties.getPreprocess() != null && videoProperties.getPreprocess().isEnabled();
    }

    /**
     * 调用音频训练模块的检测服务
     * 音频训练模块返回格式：
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "label": "bonafide|spoof",
     *     "spoof_prob": 0.85,
     *     "bonafide_prob": 0.15,
     *     "confidence": 0.85,
     *     "risk_level": "high|medium|low",
     *     "latency_ms": 150.2
     *   }
     * }
     */
    @SuppressWarnings("unchecked")
    private AudioDetectionResult callAudioDetection(String filePath, String url) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("文件不存在: " + filePath);
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = buildMultipartRequest(file);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );
            
            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                throw new RuntimeException("音频检测服务返回空响应");
            }
            
            Number codeNum = (Number) responseBody.get("code");
            int code = codeNum != null ? codeNum.intValue() : -1;
            if (code != 0) {
                String message = (String) responseBody.getOrDefault("message", "未知错误");
                throw new RuntimeException("音频检测失败: " + message);
            }
            
            Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
            if (data == null) {
                throw new RuntimeException("音频检测服务返回空数据");
            }
            
            // 解析音频训练模块的响应
            String label = (String) data.get("label");
            Double spoofProb = parseDouble(data.get("spoof_prob"));
            Double bonafideProb = parseDouble(data.get("bonafide_prob"));
            Double confidence = parseDouble(data.get("confidence"));
            String riskLevel = (String) data.get("risk_level");
            Double latencyMs = parseDouble(data.get("latency_ms"));
            String modelVersion = (String) data.get("model_version");
            String device = (String) data.get("device");
            
            AudioDetectionResult result = AudioDetectionResult.fromTrainingModule(
                    label, spoofProb, bonafideProb, confidence, riskLevel, latencyMs, modelVersion, device
            );
            enrichAudioMetadata(result, data, file.getName(), file.length());
            
            log.info("音频检测成功: label={}, spoofProb={}, confidence={}", 
                    label, spoofProb, confidence);
            return result;
            
        } catch (Exception e) {
            log.error("调用音频检测服务失败: {}", url, e);
            throw new RuntimeException("音频检测服务调用失败: " + e.getMessage(), e);
        }
    }

    private Double parseDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 直接调用 Python 视频检测服务（不走帧预处理管道）。
     * Flask 返回格式: {"success":true,"data":{"is_fake":...,"average_fake_probability":...,...}}
     * 手动解析 Map 避免 Jackson snake_case/camelCase 不匹配。
     */
    @SuppressWarnings("unchecked")
    private VideoDetectionResult callVideoDetection(String filePath, String url) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("文件不存在: " + filePath);
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = buildMultipartRequest(file);

        try {
            ResponseEntity<Map> response = videoRestTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, Map.class);

            Map<String, Object> raw = response.getBody();
            if (raw == null) {
                throw new RuntimeException("视频检测服务返回空响应");
            }

            // 统一响应格式: {code: 0, message: "success", data: {...}}
            Object codeObj = raw.get("code");
            if (codeObj instanceof Number && ((Number) codeObj).intValue() != 0) {
                throw new RuntimeException("视频检测失败: " + raw.getOrDefault("message", "未知错误"));
            }
            // 兼容旧格式: {success: true, data: {...}}
            if (Boolean.FALSE.equals(raw.get("success"))) {
                throw new RuntimeException("视频检测失败: " + raw.getOrDefault("error", "未知错误"));
            }

            Map<String, Object> data = (Map<String, Object>) raw.get("data");
            if (data == null) {
                throw new RuntimeException("视频检测服务返回空数据");
            }

            VideoDetectionResult result = new VideoDetectionResult();
            result.setType("video");

            Object isFake = data.get("is_fake");
            Object isUncertainObj = data.get("is_uncertain");
            Object avgFakeProb = data.get("average_fake_probability");
            Object maxFakeProb = data.get("max_fake_probability");
            Object aggregateFakeProb = data.get("aggregate_fake_probability");
            Object totalFrames = data.get("total_frames");
            Object totalFaces = data.get("total_faces");
            Object framesWithFace = data.get("frames_with_face");
            Object suspiciousFrameRatio = data.get("suspicious_frame_ratio");
            Object frameResults = data.get("frame_results");

            boolean isUncertain = isUncertainObj instanceof Boolean && (Boolean) isUncertainObj;
            double fakeProb;
            double avgProb = avgFakeProb instanceof Number ? ((Number) avgFakeProb).doubleValue() : 0.0;
            double maxProb = maxFakeProb instanceof Number ? ((Number) maxFakeProb).doubleValue() : avgProb;
            if (isUncertain) {
                // 模型没有可用的人脸证据时，把概率置 0.5，置信度置 0，
                // determination 置 uncertain，让 LLM/前端知道这是"无法判定"
                fakeProb = 0.5;
                result.setConfidence(0.0);
                result.setDetermination("uncertain");
            } else {
                double suspiciousRatio = suspiciousFrameRatio instanceof Number
                        ? ((Number) suspiciousFrameRatio).doubleValue()
                        : 0.0;
                fakeProb = aggregateFakeProb instanceof Number
                        ? ((Number) aggregateFakeProb).doubleValue()
                        : aggregateVideoRisk(avgProb, maxProb, suspiciousRatio,
                        isFake instanceof Boolean && (Boolean) isFake);
                result.setVisualFakeProbability(fakeProb);
                // 置信度是模型对该方向的确信程度，不等于“真实证明”。
                double certainty = Math.max(Math.abs(avgProb - 0.5) * 2, Math.abs(maxProb - 0.5) * 1.4);
                result.setConfidence(Math.max(0.35, Math.min(0.98, certainty)));
                result.setDetermination(fakeProb >= 0.55 ? "fake" : "real");
            }
            result.setFakeProbability(fakeProb);
            if (result.getVisualFakeProbability() == null) {
                result.setVisualFakeProbability(fakeProb);
            }
            result.setAverageFakeProbability(avgProb);
            result.setMaxFakeProbability(maxProb);
            result.setSuspiciousFrameRatio(suspiciousFrameRatio instanceof Number
                    ? ((Number) suspiciousFrameRatio).doubleValue()
                    : null);
            result.setTotalFrames(parseInteger(totalFrames));
            result.setTotalFaces(parseInteger(totalFaces));
            result.setFramesWithFace(parseInteger(framesWithFace));

            // 从 Flask 返回的 frame_results 重建 FrameAnalysis 列表，
            // 否则 LLM 看到 frameAnalysis=null，报告里写"分析帧数为 0"
            java.util.List<VideoDetectionResult.FrameAnalysis> analyses = new java.util.ArrayList<>();
            int synthesizedIdx = 0;
            if (frameResults instanceof java.util.List) {
                for (Object frObj : (java.util.List<?>) frameResults) {
                    if (!(frObj instanceof Map)) continue;
                    Map<String, Object> fr = (Map<String, Object>) frObj;
                    Object faces = fr.get("faces");
                    if (!(faces instanceof java.util.List) || ((java.util.List<?>) faces).isEmpty()) {
                        continue;
                    }
                    for (Object fObj : (java.util.List<?>) faces) {
                        if (!(fObj instanceof Map)) continue;
                        Map<String, Object> f = (Map<String, Object>) fObj;
                        Object fpObj = f.get("fake_probability");
                        if (fpObj instanceof Number) {
                            VideoDetectionResult.FrameAnalysis fa = new VideoDetectionResult.FrameAnalysis();
                            fa.setFrameIndex(synthesizedIdx++);
                            fa.setFakeProbability(((Number) fpObj).doubleValue());
                            analyses.add(fa);
                        }
                    }
                }
            }
            if (!analyses.isEmpty()) {
                result.setFrameAnalysis(analyses);
            }

            result.computeProbabilities();

            log.info("视频检测成功(直连): fakeProbability={}, frames={}, faces={}, frameAnalyses={}",
                    fakeProb, totalFrames, totalFaces, analyses.size());
            return result;
        } catch (ResourceAccessException e) {
            log.error("视频检测服务不可访问: {}", url, e);
            throw new RuntimeException("视频检测服务未启动或不可访问，请确认 SafeGuard-Video-Service 窗口正在运行，并检查 http://localhost:5002/api/health", e);
        } catch (Exception e) {
            log.error("调用视频检测服务失败: {}", url, e);
            throw new RuntimeException("视频检测服务调用失败: " + e.getMessage(), e);
        }
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(File file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    /**
     * 附件上传到音频检测服务（跨容器安全，直接传输字节流）
     */
    @SuppressWarnings("unchecked")
    private AudioDetectionResult callAudioDetectionWithMultipart(MultipartFile multipartFile, String url) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new org.springframework.core.io.ByteArrayResource(multipartFile.getBytes()) {
                @Override
                public String getFilename() {
                    return multipartFile.getOriginalFilename();
                }
            });

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                throw new RuntimeException("音频检测服务返回空响应");
            }

            Number codeNum = (Number) responseBody.get("code");
            int code = codeNum != null ? codeNum.intValue() : -1;
            if (code != 0) {
                String message = (String) responseBody.getOrDefault("message", "未知错误");
                throw new RuntimeException("音频检测失败: " + message);
            }

            Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
            if (data == null) {
                throw new RuntimeException("音频检测服务返回空数据");
            }

            String label = (String) data.get("label");
            Double spoofProb = parseDouble(data.get("spoof_prob"));
            Double bonafideProb = parseDouble(data.get("bonafide_prob"));
            Double confidence = parseDouble(data.get("confidence"));
            String riskLevel = (String) data.get("risk_level");
            Double latencyMs = parseDouble(data.get("latency_ms"));
            String modelVersion = (String) data.get("model_version");
            String device = (String) data.get("device");

            AudioDetectionResult result = AudioDetectionResult.fromTrainingModule(
                    label, spoofProb, bonafideProb, confidence, riskLevel, latencyMs, modelVersion, device);
            enrichAudioMetadata(result, data, multipartFile.getOriginalFilename(), multipartFile.getSize());

            log.info("音频检测成功(跨容器): label={}, spoofProb={}, confidence={}",
                    label, spoofProb, confidence);
            return result;

        } catch (Exception e) {
            log.error("调用音频检测服务失败: {}", url, e);
            throw new RuntimeException("音频检测服务调用失败: " + e.getMessage(), e);
        }
    }

    private double aggregateVideoRisk(double avgProb, double maxProb, double suspiciousRatio, boolean serviceIsFake) {
        double risk = Math.max(avgProb, maxProb * 0.82);
        if (suspiciousRatio >= 0.25) {
            risk = Math.max(risk, 0.62 + Math.min(0.18, suspiciousRatio * 0.25));
        } else if (suspiciousRatio > 0) {
            risk = Math.max(risk, 0.45 + suspiciousRatio * 0.5);
        }
        if (serviceIsFake) {
            risk = Math.max(risk, 0.58);
        }
        return Math.max(0.0, Math.min(1.0, risk));
    }

    private void enrichAudioMetadata(AudioDetectionResult result, Map<String, Object> data,
                                     String fileName, long fileSizeBytes) {
        result.setFileName(fileName);
        result.setFileSizeBytes(fileSizeBytes);
        result.setSampleRate(parseInteger(data.get("sample_rate")));
        result.setOriginalSampleRate(parseInteger(data.get("original_sample_rate")));
        result.setChannels(parseInteger(data.get("channels")));
        result.setDurationSeconds(parseDouble(data.get("duration_seconds")));
        result.setAnalyzedSeconds(parseDouble(data.get("analyzed_seconds")));
        result.setMaxSeconds(parseDouble(data.get("max_seconds")));
        Object truncated = data.get("truncated");
        if (truncated instanceof Boolean b) {
            result.setTruncated(b);
        } else if (truncated != null) {
            result.setTruncated(Boolean.parseBoolean(truncated.toString()));
        }
    }

    private Integer parseInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
