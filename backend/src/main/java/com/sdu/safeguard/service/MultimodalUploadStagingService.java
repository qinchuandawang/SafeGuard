package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalUploadStagingService {

    private final ObjectStorageService objectStorageService;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final Map<String, StagedAudio> localStages = new ConcurrentHashMap<>();

    @Value("${task.multimodal-stage.ttl-seconds:600}")
    private long ttlSeconds;

    public String stage(MultipartFile audio, String fileHash) {
        String token = UUID.randomUUID().toString();
        String objectKey = null;
        Path localPath = null;
        try {
            objectKey = objectStorageService.upload(audio, "multimodal-staging", fileHash);
            String suffix = extension(audio.getOriginalFilename());
            localPath = Files.createTempFile("safeguard-multimodal-audio-", suffix);
            audio.transferTo(localPath);
            StagedAudio staged = new StagedAudio(
                    token, audio.getOriginalFilename(), fileHash, objectKey,
                    localPath.toString(), Instant.now().plusSeconds(ttlSeconds).toEpochMilli());
            localStages.put(token, staged);
            writeRedis(staged);
            return token;
        } catch (Exception exception) {
            objectStorageService.deleteQuietly(objectKey);
            deleteQuietly(localPath);
            throw new IllegalStateException("音频暂存失败", exception);
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public StagedAudioHandle consume(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("缺少 audioToken");
        StagedAudio staged = readAndDeleteRedis(token);
        StagedAudio local = localStages.remove(token);
        if (staged == null) staged = local;
        if (staged == null || staged.expiresAtEpochMs() < System.currentTimeMillis()) {
            cleanup(local != null ? local : staged);
            throw new IllegalArgumentException("audioToken 无效或已过期");
        }
        Path localPath = staged.localPath() == null ? null : Path.of(staged.localPath());
        boolean downloaded = false;
        if (localPath == null || !Files.isRegularFile(localPath)) {
            try {
                localPath = objectStorageService.downloadToTemp(staged.objectKey());
                downloaded = true;
            } catch (RuntimeException exception) {
                cleanup(staged);
                throw new IllegalStateException("暂存音频读取失败", exception);
            }
        }
        return new StagedAudioHandle(staged, localPath, downloaded);
    }

    public void cleanup(StagedAudioHandle handle) {
        if (handle == null) return;
        deleteQuietly(handle.path());
        objectStorageService.deleteQuietly(handle.staged().objectKey());
    }

    @Scheduled(fixedDelayString = "${task.multimodal-stage.cleanup-delay-ms:60000}")
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        localStages.forEach((token, staged) -> {
            if (staged.expiresAtEpochMs() <= now && localStages.remove(token, staged)) {
                cleanup(staged);
            }
        });
    }

    private void writeRedis(StagedAudio staged) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) return;
        try {
            redis.opsForValue().set(redisKey(staged.token()), objectMapper.writeValueAsString(staged),
                    Duration.ofSeconds(ttlSeconds));
        } catch (Exception exception) {
            log.debug("写入多模态暂存 Redis 失败，使用本机映射: {}", exception.getMessage());
        }
    }

    private StagedAudio readAndDeleteRedis(String token) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) return null;
        try {
            String json = redis.opsForValue().getAndDelete(redisKey(token));
            return json == null ? null : objectMapper.readValue(json, StagedAudio.class);
        } catch (Exception exception) {
            log.debug("读取多模态暂存 Redis 失败，尝试本机映射: {}", exception.getMessage());
            return null;
        }
    }

    private void cleanup(StagedAudio staged) {
        if (staged == null) return;
        if (staged.localPath() != null) deleteQuietly(Path.of(staged.localPath()));
        objectStorageService.deleteQuietly(staged.objectKey());
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private String extension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return ".bin";
        return fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
    }

    private String redisKey(String token) {
        return "safeguard:multimodal-stage:" + token;
    }

    public record StagedAudio(String token, String fileName, String fileHash, String objectKey,
                              String localPath, long expiresAtEpochMs) {
    }

    public record StagedAudioHandle(StagedAudio staged, Path path, boolean downloaded) {
    }
}
