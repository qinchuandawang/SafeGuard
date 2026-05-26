package com.sdu.safeguard.service;

import com.sdu.safeguard.config.VideoProperties;
import com.sdu.safeguard.dto.AudioDetectionResult;
import com.sdu.safeguard.dto.VideoDetectionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.Map;
import java.util.function.BiConsumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionService {

    private final RestTemplate restTemplate;
    private final VideoImagePipelineService videoImagePipelineService;
    private final VideoProperties videoProperties;

    @Value("${audio.service.url}")
    private String audioServiceUrl;

    @Value("${video.service.url}")
    private String videoServiceUrl;

    public AudioDetectionResult detectAudio(String filePath) {
        // 调用音频训练模块的检测服务
        AudioDetectionResult result = callAudioDetection(filePath, audioServiceUrl);
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
            result = callDetection(filePath, videoServiceUrl, VideoDetectionResult.class);
        }
        if (result != null) {
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

    private <T> T callDetection(String filePath, String url, Class<T> responseType) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("文件不存在: " + filePath);
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = buildMultipartRequest(file);

        try {
            ResponseEntity<T> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    responseType
            );
            log.info("调用检测服务成功: {}", url);
            return response.getBody();
        } catch (Exception e) {
            log.error("调用检测服务失败: {}", url, e);
            throw new RuntimeException("检测服务调用失败: " + e.getMessage(), e);
        }
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(File file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }
}
