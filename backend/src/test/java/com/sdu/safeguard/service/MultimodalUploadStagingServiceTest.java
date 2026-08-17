package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultimodalUploadStagingServiceTest {

    @Test
    void 音频暂存令牌只能消费一次() {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        when(storage.upload(any(MockMultipartFile.class), any(), any())).thenReturn(null);
        MultimodalUploadStagingService service =
                new MultimodalUploadStagingService(storage, redisProvider, new ObjectMapper());
        ReflectionTestUtils.setField(service, "ttlSeconds", 600L);
        MockMultipartFile audio = new MockMultipartFile(
                "file", "voice.wav", "audio/wav", new byte[]{1, 2, 3});

        String token = service.stage(audio, "hash");
        MultimodalUploadStagingService.StagedAudioHandle handle = service.consume(token);
        try {
            assertThat(handle.path()).exists();
            assertThat(handle.staged().fileHash()).isEqualTo("hash");
        } finally {
            service.cleanup(handle);
        }
        assertThatThrownBy(() -> service.consume(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无效或已过期");
    }

    @Test
    void 远端暂存下载失败时清理对象() throws Exception {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        when(storage.upload(any(MockMultipartFile.class), any(), any())).thenReturn("stage/object.wav");
        when(storage.downloadToTemp("stage/object.wav")).thenThrow(new IllegalStateException("下载失败"));
        MultimodalUploadStagingService service =
                new MultimodalUploadStagingService(storage, redisProvider, new ObjectMapper());
        ReflectionTestUtils.setField(service, "ttlSeconds", 600L);
        MockMultipartFile audio = new MockMultipartFile(
                "file", "voice.wav", "audio/wav", new byte[]{1, 2, 3});

        String token = service.stage(audio, "hash");
        @SuppressWarnings("unchecked")
        Map<String, MultimodalUploadStagingService.StagedAudio> stages =
                (Map<String, MultimodalUploadStagingService.StagedAudio>)
                        ReflectionTestUtils.getField(service, "localStages");
        Files.deleteIfExists(java.nio.file.Path.of(stages.get(token).localPath()));

        assertThatThrownBy(() -> service.consume(token))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("暂存音频读取失败");
        verify(storage).deleteQuietly("stage/object.wav");
    }
}
