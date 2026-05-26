package com.sdu.safeguard.service;

import com.sdu.safeguard.dto.AudioDetectionResponse;
import com.sdu.safeguard.entity.AudioDetectionRecord;
import com.sdu.safeguard.entity.AudioModel;
import com.sdu.safeguard.mapper.AudioDetectionRecordMapper;
import com.sdu.safeguard.mapper.AudioModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AudioTrainingService {

    private final AudioDetectionRecordMapper audioRecordMapper;
    private final AudioModelMapper audioModelMapper;
    private final RestTemplate restTemplate;

    @Value("${audio.detection.api-url:http://localhost:5000/audio/detect}")
    private String audioApiUrl;

    @Value("${audio.detection.health-url:http://localhost:5000/health}")
    private String healthUrl;

    /**
     * 检查音频检测服务是否可用
     */
    public boolean isServiceAvailable() {
        try {
            restTemplate.getForObject(healthUrl, Map.class);
            return true;
        } catch (RestClientException e) {
            log.warn("音频检测服务不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检测音频是否为伪造
     */
    @Transactional
    public AudioDetectionRecord detectAudio(MultipartFile file, Long userId) throws IOException {
        String taskId = UUID.randomUUID().toString();

        // 保存上传的文件
        Path tempDir = Files.createTempDirectory("audio_detection_");
        String originalFilename = file.getOriginalFilename();
        Path tempFile = tempDir.resolve(originalFilename != null ? originalFilename : "audio.wav");
        file.transferTo(tempFile.toFile());

        AudioDetectionRecord record = AudioDetectionRecord.builder()
                .taskId(taskId)
                .userId(userId)
                .fileName(originalFilename)
                .fileSize(file.getSize())
                .filePath(tempFile.toString())
                .status("processing")
                .build();
        audioRecordMapper.insert(record);

        try {
            // 调用音频检测 API
            AudioDetectionResponse response = callAudioDetectionApi(tempFile.toFile());

            // 更新检测结果
            record.setDetectionResult(response.getLabel());
            record.setSpoofProbability(response.getSpoofProb());
            record.setBonafideProbability(response.getBonafideProb());
            record.setConfidence(response.getConfidence());
            record.setRiskLevel(response.getRiskLevel());
            record.setModelVersion(response.getModelVersion());
            record.setDetectionLatencyMs(response.getLatencyMs());
            record.setStatus("success");

            // 优先使用 DB 中登记的模型版本（语义化），否则使用 API 返回的路径版本
            AudioModel activeModel = audioModelMapper.findActiveModel();
            if (activeModel != null && activeModel.getModelVersion() != null) {
                record.setModelVersion(activeModel.getModelVersion());
            }

            log.info("音频检测成功: taskId={}, result={}, spoofProb={}",
                    taskId, response.getLabel(), response.getSpoofProb());

        } catch (Exception e) {
            record.setStatus("failed");
            record.setErrorMessage(e.getMessage());
            log.error("音频检测失败: taskId={}, error={}", taskId, e.getMessage());
        } finally {
            // 清理临时文件
            try {
                Files.deleteIfExists(tempFile);
                Files.deleteIfExists(tempDir);
            } catch (IOException ignored) {}
        }

        audioRecordMapper.updateById(record);
        return record;
    }

    /**
     * 调用音频检测 API
     */
    private AudioDetectionResponse callAudioDetectionApi(java.io.File audioFile) {
        org.springframework.core.io.FileSystemResource resource =
                new org.springframework.core.io.FileSystemResource(audioFile);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    audioApiUrl,
                    body,
                    Map.class
            );

            if (response == null || !Integer.valueOf(0).equals(response.get("code"))) {
                throw new RuntimeException("音频检测API返回错误: " + response);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) {
                throw new RuntimeException("音频检测API返回 data 为空");
            }

            Number spoofProb = data.get("spoof_prob") instanceof Number ? (Number) data.get("spoof_prob") : 0.0;
            Number bonafideProb = data.get("bonafide_prob") instanceof Number ? (Number) data.get("bonafide_prob") : 0.0;
            Number confidence = data.get("confidence") instanceof Number ? (Number) data.get("confidence") : 0.0;
            Number latencyMs = data.get("latency_ms") instanceof Number ? (Number) data.get("latency_ms") : 0.0;

            return AudioDetectionResponse.builder()
                    .label((String) data.get("label"))
                    .spoofProb(spoofProb.doubleValue())
                    .bonafideProb(bonafideProb.doubleValue())
                    .confidence(confidence.doubleValue())
                    .riskLevel((String) data.get("risk_level"))
                    .latencyMs(latencyMs.doubleValue())
                    .modelVersion((String) data.get("model_version"))
                    .device((String) data.get("device"))
                    .build();
        } catch (RestClientException e) {
            throw new RuntimeException("无法连接到音频检测服务: " + e.getMessage());
        }
    }

    /**
     * 根据任务ID获取检测记录
     */
    public Optional<AudioDetectionRecord> getRecordByTaskId(String taskId) {
        return Optional.ofNullable(audioRecordMapper.findByTaskId(taskId));
    }

    /**
     * 获取用户的所有检测记录
     */
    public List<AudioDetectionRecord> getUserRecords(Long userId) {
        return audioRecordMapper.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 获取所有检测记录
     */
    public List<AudioDetectionRecord> getAllRecords() {
        return audioRecordMapper.selectList(null);
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRecords", audioRecordMapper.selectCount(null));
        stats.put("spoofCount", audioRecordMapper.countByDetectionResult("spoof"));
        stats.put("bonafideCount", audioRecordMapper.countByDetectionResult("bonafide"));
        stats.put("pendingCount", audioRecordMapper.countByStatus("pending"));
        stats.put("processingCount", audioRecordMapper.countByStatus("processing"));
        stats.put("successCount", audioRecordMapper.countByStatus("success"));
        stats.put("failedCount", audioRecordMapper.countByStatus("failed"));
        stats.put("serviceAvailable", isServiceAvailable());
        return stats;
    }

    /**
     * 保存模型信息
     */
    @Transactional
    public AudioModel saveModel(AudioModel model) {
        // 如果设为活跃模型，先取消其他活跃模型
        if (Boolean.TRUE.equals(model.getIsActive())) {
            AudioModel existing = audioModelMapper.findActiveModel();
            if (existing != null) {
                existing.setIsActive(false);
                audioModelMapper.updateById(existing);
            }
        }
        audioModelMapper.insert(model);
        return model;
    }

    /**
     * 获取当前活跃模型
     */
    public Optional<AudioModel> getActiveModel() {
        return Optional.ofNullable(audioModelMapper.findActiveModel());
    }

    /**
     * 获取所有模型
     */
    public List<AudioModel> getAllModels() {
        return audioModelMapper.selectList(null);
    }
}
