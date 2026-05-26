package com.sdu.safeguard.service;

import com.sdu.safeguard.config.VideoProperties;
import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.dto.VideoImageInferenceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoImagePipelineService {

    private static final String TEMP_ROOT = System.getProperty("java.io.tmpdir") + "/safe_guard/";

    private final RestTemplate restTemplate;
    private final VideoProperties videoProperties;
    private final VideoFrameExtractorService videoFrameExtractorService;
    private final FaceCropService faceCropService;

    public VideoDetectionResult detectFromVideoFile(String videoPath) {
        return detectFromVideoFile(videoPath, null);
    }

    public VideoDetectionResult detectFromVideoFile(String videoPath,
                                                     BiConsumer<Integer, Map<String, Object>> progressCallback) {
        File videoFile = new File(videoPath);
        File workDir = new File(TEMP_ROOT, "pipeline_" + UUID.randomUUID());
        try {
            if (!workDir.mkdirs() && !workDir.isDirectory()) {
                throw new RuntimeException("无法创建预处理目录: " + workDir.getAbsolutePath());
            }

            var pre = videoProperties.getPreprocess();
            List<File> frames = videoFrameExtractorService.extractFrames(
                    videoFile,
                    workDir,
                    pre.getSampleIntervalSeconds(),
                    pre.getMaxFrames()
            );

            List<VideoDetectionResult.FrameAnalysis> analyses = new ArrayList<>();
            double maxProb = 0.0;
            double sumProb = 0.0;
            int counted = 0;
            String dominantFakeType = null;

            for (int i = 0; i < frames.size(); i++) {
                File frameFile = frames.get(i);
                File cropFile = new File(workDir, "face_" + i + ".png");
                File toSend;
                try {
                    boolean hasFace = faceCropService.cropLargestFace(
                            frameFile, cropFile, pre.getFacePaddingRatio());
                    toSend = hasFace ? cropFile : frameFile;
                } catch (Exception e) {
                    log.warn("第 {} 帧人脸裁剪异常，改用整帧: {}", i, e.getMessage());
                    toSend = frameFile;
                }

                try {
                    VideoImageInferenceResponse inf = callImageInference(toSend);
                    double p = clamp01(inf.resolveFakeProbability());
                    VideoDetectionResult.FrameAnalysis fa = new VideoDetectionResult.FrameAnalysis();
                    fa.setFrameIndex(i);
                    fa.setFakeProbability(p);
                    analyses.add(fa);
                    maxProb = Math.max(maxProb, p);
                    sumProb += p;
                    counted++;
                    if (dominantFakeType == null && inf.getFakeType() != null && !inf.getFakeType().isBlank()) {
                        dominantFakeType = inf.getFakeType();
                    }
                } catch (Exception e) {
                    log.warn("第 {} 帧模型调用失败: {}", i, e.getMessage());
                }

                if (progressCallback != null) {
                    int pct = (int) ((i + 1) * 100.0 / frames.size());
                    progressCallback.accept(pct, Map.of(
                            "frame", i + 1,
                            "totalFrames", frames.size(),
                            "message", "正在分析第 " + (i + 1) + "/" + frames.size() + " 帧..."
                    ));
                }
            }

            if (counted == 0) {
                throw new RuntimeException("所有帧均未获得有效推理结果，请检查 video.service.image-url 与 Flask 服务");
            }

            VideoDetectionResult merged = new VideoDetectionResult();
            merged.setType("video");
            merged.setFakeProbability(maxProb);
            merged.setConfidence(avgConfidence(sumProb, counted));
            merged.setFakeType(dominantFakeType != null ? dominantFakeType : "face_frame_aggregated");
            merged.computeProbabilities();
            analyses.sort(Comparator.comparing(VideoDetectionResult.FrameAnalysis::getFrameIndex));
            merged.setFrameAnalysis(analyses);
            log.info("视频图片链路完成，有效帧 {} / {}，聚合 fakeProbability(max)={}", counted, frames.size(), maxProb);
            return merged;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("视频预处理失败: " + e.getMessage(), e);
        } finally {
            deleteRecursiveQuietly(workDir);
        }
    }

    private String resolveImageUrl() {
        String image = videoProperties.getService().getImageUrl();
        if (image != null && !image.isBlank()) {
            return image.trim();
        }
        return videoProperties.getService().getUrl();
    }

    private VideoImageInferenceResponse callImageInference(File file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(videoProperties.getPreprocess().getMultipartField(), new FileSystemResource(file));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<VideoImageInferenceResponse> resp = restTemplate.exchange(
                resolveImageUrl(),
                HttpMethod.POST,
                entity,
                VideoImageInferenceResponse.class
        );
        VideoImageInferenceResponse r = resp.getBody();
        if (r == null) {
            throw new RuntimeException("Flask 返回空 body");
        }
        return r;
    }

    private static double clamp01(double p) {
        if (p < 0) {
            return 0;
        }
        if (p > 1) {
            return 1;
        }
        return p;
    }

    private static double avgConfidence(double sumProb, int n) {
        if (n <= 0) {
            return 0.0;
        }
        double avg = sumProb / n;
        return clamp01(Math.abs(avg - 0.5) * 2);
    }

    private static void deleteRecursiveQuietly(File root) {
        if (root == null || !root.exists()) {
            return;
        }
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursiveQuietly(c);
                }
            }
        }
        if (!root.delete()) {
            log.debug("临时文件未删除（占用中可忽略）: {}", root.getAbsolutePath());
        }
    }
}
