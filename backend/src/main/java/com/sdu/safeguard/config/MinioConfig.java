package com.sdu.safeguard.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {
    @Bean
    @ConditionalOnProperty(prefix = "storage.minio", name = "enabled", havingValue = "true")
    public MinioClient minioClient(@Value("${storage.minio.endpoint}") String endpoint,
                                   @Value("${storage.minio.access-key}") String accessKey,
                                   @Value("${storage.minio.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
