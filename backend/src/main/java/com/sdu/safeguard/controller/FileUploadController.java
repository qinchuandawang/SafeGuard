package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.FileUploadResponse;
import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.service.DetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class FileUploadController {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/safe_guard/";
    private static final long MAX_AUDIO_SIZE = 20L * 1024 * 1024;
    private static final long MAX_VIDEO_SIZE = 100L * 1024 * 1024;
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(".wav", ".flac", ".mp3", ".m4a", ".ogg");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".avi", ".mkv", ".webm");

    private final DetectionService detectionService;

    @PostMapping
    public Result<FileUploadResponse> uploadFile(@RequestParam("file") MultipartFile file,
                                                 @RequestParam("type") String type) {
        String validationError = validateRequest(file, type);
        if (validationError != null) {
            return Result.error(validationError);
        }

        try {
            SavedFile savedFile = saveToTemp(file);
            FileUploadResponse response = buildUploadResponse(savedFile, type, file.getSize());
            log.info("文件上传成功: {}, 类型: {}", savedFile.fileId(), type);
            return Result.success(response);
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return Result.error("文件上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/detect")
    public Result<?> uploadAndDetect(@RequestParam("file") MultipartFile file,
                                     @RequestParam("type") String type) {
        String validationError = validateRequest(file, type);
        if (validationError != null) {
            return Result.error(validationError);
        }

        SavedFile savedFile = null;
        try {
            savedFile = saveToTemp(file);
            Object detectionResult = detectByType(type, savedFile.destFile().getAbsolutePath());
            return Result.success(detectionResult);
        } catch (IOException e) {
            log.error("文件处理失败", e);
            return Result.error("文件处理失败");
        } catch (RuntimeException e) {
            log.error("检测服务调用失败", e);
            return Result.error("检测失败: " + e.getMessage());
        } finally {
            if (savedFile != null && savedFile.destFile().exists()) {
                deleteQuietly(savedFile.destFile().getAbsolutePath());
            }
        }
    }

    private static void deleteQuietly(String filePath) {
        if (filePath == null) return;
        File f = new File(filePath);
        if (f.exists() && !f.delete()) {
            log.debug("临时文件删除失败（可能被占用）: {}", filePath);
        }
    }

    private String validateRequest(MultipartFile file, String type) {
        if (file == null || file.isEmpty()) {
            return "文件不能为空";
        }
        if (!isSupportedType(type)) {
            return "type必须为audio或video";
        }
        String extension = extractExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if ("audio".equalsIgnoreCase(type)) {
            if (file.getSize() > MAX_AUDIO_SIZE) {
                return "音频文件不能超过20MB";
            }
            if (!AUDIO_EXTENSIONS.contains(extension)) {
                return "不支持的音频格式";
            }
        }
        if ("video".equalsIgnoreCase(type)) {
            if (file.getSize() > MAX_VIDEO_SIZE) {
                return "视频文件不能超过100MB";
            }
            if (!VIDEO_EXTENSIONS.contains(extension)) {
                return "不支持的视频格式";
            }
        }
        return null;
    }

    private boolean isSupportedType(String type) {
        return "audio".equalsIgnoreCase(type) || "video".equalsIgnoreCase(type);
    }

    private SavedFile saveToTemp(MultipartFile file) throws IOException {
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + tempDir.getAbsolutePath());
        }

        String originalName = file.getOriginalFilename();
        String extension = extractExtension(originalName);
        String fileId = UUID.randomUUID().toString();
        File destFile = new File(tempDir, fileId + extension);
        file.transferTo(destFile);
        return new SavedFile(fileId, originalName, destFile);
    }

    private String extractExtension(String originalName) {
        if (originalName == null || !originalName.contains(".")) {
            return "";
        }
        return originalName.substring(originalName.lastIndexOf('.'));
    }

    private FileUploadResponse buildUploadResponse(SavedFile savedFile, String type, long size) {
        FileUploadResponse response = new FileUploadResponse();
        response.setFileId(savedFile.fileId());
        response.setOriginalName(savedFile.originalName());
        response.setFileType(type);
        response.setSize(size);
        return response;
    }

    private Object detectByType(String type, String filePath) {
        return "audio".equalsIgnoreCase(type)
                ? detectionService.detectAudio(filePath)
                : detectionService.detectVideo(filePath);
    }

    private record SavedFile(String fileId, String originalName, File destFile) {
    }
}
