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
import org.springframework.web.multipart.MultipartFile;

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
            result = callVideoDetection(filePath, videoServiceUrl);
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
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, Map.class);

            Map<String, Object> raw = response.getBody();
            if (raw == null) {
                throw new RuntimeException("视频检测服务返回空响应");
            }

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
            Object avgFakeProb = data.get("average_fake_probability");
            Object maxFakeProb = data.get("max_fake_probability");
            Object totalFrames = data.get("total_frames");

            double fakeProb = avgFakeProb instanceof Number ? ((Number) avgFakeProb).doubleValue()
                    : (isFake instanceof Boolean && (Boolean) isFake ? 1.0 : 0.0);
            result.setFakeProbability(fakeProb);

            double confidence = Math.abs(fakeProb - 0.5) * 2;
            result.setConfidence(confidence);

            result.computeProbabilities();

            log.info("视频检测成功(直连): fakeProbability={}, frames={}", fakeProb, totalFrames);
            return result;
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

            log.info("音频检测成功(跨容器): label={}, spoofProb={}, confidence={}",
                    label, spoofProb, confidence);
            return result;

        } catch (Exception e) {
            log.error("调用音频检测服务失败: {}", url, e);
            throw new RuntimeException("音频检测服务调用失败: " + e.getMessage(), e);
        }
    }
}
