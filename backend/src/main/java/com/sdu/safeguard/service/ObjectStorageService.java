package com.sdu.safeguard.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.GetObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ObjectStorageService {

    private final ObjectProvider<MinioClient> minioClientProvider;

    @Value("${storage.minio.enabled:false}")
    private boolean enabled;

    @Value("${storage.minio.bucket:safeguard-media}")
    private String bucket;

    public String upload(MultipartFile file, String category, String fileHash) {
        if (!enabled) return null;
        MinioClient client = minioClientProvider.getIfAvailable();
        if (client == null) throw new IllegalStateException("MinIO 客户端未配置");
        String originalName = file.getOriginalFilename() == null ? "media.bin" : file.getOriginalFilename();
        String suffix = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : "";
        String objectKey = category + "/" + LocalDate.now() + "/" + fileHash + "-" + UUID.randomUUID() + suffix;
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            try (java.io.InputStream inputStream = file.getInputStream()) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(inputStream, file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build());
            }
            return objectKey;
        } catch (Exception exception) {
            throw new IllegalStateException("原始媒体写入对象存储失败", exception);
        }
    }

    public String upload(Path file, String category, String fileHash, String contentType) {
        if (!enabled) return null;
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("待上传文件不存在");
        }
        MinioClient client = minioClientProvider.getIfAvailable();
        if (client == null) throw new IllegalStateException("MinIO 客户端未配置");
        String fileName = file.getFileName().toString();
        String suffix = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        String objectKey = category + "/" + LocalDate.now() + "/" + fileHash + "-" + UUID.randomUUID() + suffix;
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            try (java.io.InputStream inputStream = Files.newInputStream(file)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(inputStream, Files.size(file), -1)
                        .contentType(contentType)
                        .build());
            }
            return objectKey;
        } catch (Exception exception) {
            throw new IllegalStateException("任务包写入对象存储失败", exception);
        }
    }

    public void deleteQuietly(String objectKey) {
        if (!enabled || objectKey == null || objectKey.isBlank()) return;
        MinioClient client = minioClientProvider.getIfAvailable();
        if (client == null) return;
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception ignored) {
            // 补偿删除失败由对象存储生命周期规则最终清理。
        }
    }

    public Path downloadToTemp(String objectKey) {
        if (!enabled || objectKey == null || objectKey.isBlank()) {
            throw new IllegalStateException("对象存储未启用或对象 Key 为空");
        }
        MinioClient client = minioClientProvider.getIfAvailable();
        if (client == null) throw new IllegalStateException("MinIO 客户端未配置");
        String suffix = objectKey.contains(".") ? objectKey.substring(objectKey.lastIndexOf('.')) : ".bin";
        try {
            Path target = Files.createTempFile("safeguard-recovery-", suffix);
            try (java.io.InputStream inputStream = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build())) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (Exception exception) {
            throw new IllegalStateException("从对象存储恢复媒体失败", exception);
        }
    }
}
