package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultimodalMediaBundleServiceTest {

    private final MultimodalMediaBundleService service =
            new MultimodalMediaBundleService(new ObjectMapper());

    @Test
    void 任务包往返保持媒体和模型元数据() throws Exception {
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "voice.wav", "audio/wav", new byte[]{1, 2, 3});
        MockMultipartFile video = new MockMultipartFile(
                "video", "face.mp4", "video/mp4", new byte[]{4, 5, 6});
        Path bundle = service.create(audio, video, "请核实转账", "audio-model", "video-model");
        MultimodalMediaBundleService.ExtractedBundle extracted = null;
        try {
            extracted = service.extract(bundle);
            assertThat(Files.readAllBytes(extracted.audioPath())).containsExactly(1, 2, 3);
            assertThat(Files.readAllBytes(extracted.videoPath())).containsExactly(4, 5, 6);
            assertThat(extracted.metadata().text()).isEqualTo("请核实转账");
            assertThat(extracted.metadata().audioModelId()).isEqualTo("audio-model");
            assertThat(extracted.metadata().videoModelId()).isEqualTo("video-model");
        } finally {
            service.cleanup(extracted);
            Files.deleteIfExists(bundle);
        }
    }

    @Test
    void 拒绝包含路径穿越条目的任务包() throws Exception {
        Path bundle = Files.createTempFile("unsafe-multimodal-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("../outside.txt"));
            zip.write(new byte[]{1});
            zip.closeEntry();
        }
        try {
            assertThatThrownBy(() -> service.extract(bundle))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("非法条目");
        } finally {
            Files.deleteIfExists(bundle);
        }
    }
}
