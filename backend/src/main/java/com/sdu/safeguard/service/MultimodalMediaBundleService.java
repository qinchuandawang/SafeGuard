package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class MultimodalMediaBundleService {

    private static final Set<String> METADATA_ENTRY = Set.of("metadata.json");
    private static final long MAX_EXTRACTED_BYTES = 160L * 1024 * 1024;
    private final ObjectMapper objectMapper;

    public Path create(MultipartFile audio, MultipartFile video, String text,
                       String audioModelId, String videoModelId) throws IOException {
        return create(audio.getInputStream(), audio.getOriginalFilename(), video, text,
                audioModelId, videoModelId);
    }

    public Path create(Path audio, String audioFileName, MultipartFile video, String text,
                       String audioModelId, String videoModelId) throws IOException {
        return create(Files.newInputStream(audio), audioFileName, video, text,
                audioModelId, videoModelId);
    }

    private Path create(InputStream audioInput, String audioFileName, MultipartFile video, String text,
                        String audioModelId, String videoModelId) throws IOException {
        Path bundle = Files.createTempFile("safeguard-multimodal-", ".zip");
        String audioEntry = "audio" + extension(audioFileName);
        String videoEntry = "video" + extension(video.getOriginalFilename());
        try (OutputStream output = Files.newOutputStream(bundle);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            writeJson(zip, "metadata.json", Map.of(
                    "text", text == null ? "" : text,
                    "audioEntry", audioEntry,
                    "videoEntry", videoEntry,
                    "audioModelId", audioModelId == null ? "" : audioModelId,
                    "videoModelId", videoModelId == null ? "" : videoModelId
            ));
            writeFile(zip, audioEntry, audioInput);
            writeFile(zip, videoEntry, video.getInputStream());
            return bundle;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(bundle);
            throw exception;
        }
    }

    public ExtractedBundle extract(Path bundle) throws IOException {
        if (bundle == null || !Files.isRegularFile(bundle)) {
            throw new IOException("多模态任务包不存在");
        }
        Path directory = Files.createTempDirectory("safeguard-multimodal-extract-");
        Path metadataPath = null;
        Path audioPath = null;
        Path videoPath = null;
        long extractedBytes = 0;
        Set<String> seenEntries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundle))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !isAllowedEntry(name) || !seenEntries.add(name)) {
                    throw new IOException("多模态任务包包含非法条目: " + name);
                }
                Path target = directory.resolve(name).normalize();
                if (!target.startsWith(directory)) {
                    throw new IOException("多模态任务包路径越界");
                }
                extractedBytes += copyLimited(zip, target, MAX_EXTRACTED_BYTES - extractedBytes);
                if (extractedBytes > MAX_EXTRACTED_BYTES) {
                    throw new IOException("多模态任务包解压后超过大小限制");
                }
                if ("metadata.json".equals(name)) metadataPath = target;
                else if (name.startsWith("audio.")) audioPath = target;
                else if (name.startsWith("video.")) videoPath = target;
            }
        } catch (IOException | RuntimeException exception) {
            deleteDirectory(directory);
            throw exception;
        }
        if (metadataPath == null || audioPath == null || videoPath == null) {
            deleteDirectory(directory);
            throw new IOException("多模态任务包缺少必要文件");
        }
        BundleMetadata metadata = objectMapper.readValue(metadataPath.toFile(), BundleMetadata.class);
        if (!audioPath.getFileName().toString().equals(metadata.audioEntry())
                || !videoPath.getFileName().toString().equals(metadata.videoEntry())) {
            deleteDirectory(directory);
            throw new IOException("多模态任务包元数据与媒体条目不一致");
        }
        return new ExtractedBundle(directory, audioPath, videoPath, metadata);
    }

    public void cleanup(ExtractedBundle bundle) {
        if (bundle != null) deleteDirectory(bundle.directory());
    }

    private boolean isAllowedEntry(String name) {
        return METADATA_ENTRY.contains(name) || name.matches("audio\\.[A-Za-z0-9]+")
                || name.matches("video\\.[A-Za-z0-9]+") ;
    }

    private long copyLimited(InputStream input, Path target, long remaining) throws IOException {
        if (remaining < 0) throw new IOException("多模态任务包解压后超过大小限制");
        long copied = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(target)) {
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length == 0) continue;
                copied += length;
                if (copied > remaining) throw new IOException("多模态任务包解压后超过大小限制");
                output.write(buffer, 0, length);
            }
        }
        return copied;
    }

    private void writeJson(ZipOutputStream zip, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(objectMapper.writeValueAsBytes(value));
        zip.closeEntry();
    }

    private void writeFile(ZipOutputStream zip, String name, InputStream input) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (input) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private String extension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return ".bin";
        return fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
    }

    private void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    public record BundleMetadata(String text, String audioEntry, String videoEntry,
                                 String audioModelId, String videoModelId) {
    }

    public record ExtractedBundle(Path directory, Path audioPath, Path videoPath, BundleMetadata metadata) {
    }
}
