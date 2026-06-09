package com.sdu.safeguard.service;

import com.sdu.safeguard.config.VideoProperties;
import com.sdu.safeguard.dto.VideoDetectionResult;
import com.sdu.safeguard.dto.VideoImageInferenceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class VideoImagePipelineService {

    private static final String TEMP_ROOT = System.getProperty("java.io.tmpdir") + "/safe_guard/";

    private final RestTemplate restTemplate;
    private final VideoProperties videoProperties;
    private final VideoFrameExtractorService videoFrameExtractorService;
    private final FaceCropService faceCropService;
    private final Executor videoFrameExecutor;

    public VideoImagePipelineService(RestTemplate restTemplate,
                                     VideoProperties videoProperties,
                                     VideoFrameExtractorService videoFrameExtractorService,
                                     FaceCropService faceCropService,
                                     @org.springframework.beans.factory.annotation.Qualifier("videoFrameExecutor") Executor videoFrameExecutor) {
        this.restTemplate = restTemplate;
        this.videoProperties = videoProperties;
        this.videoFrameExtractorService = videoFrameExtractorService;
        this.faceCropService = faceCropService;
        this.videoFrameExecutor = videoFrameExecutor;
    }

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

            List<VideoDetectionResult.FrameAnalysis> analyses = Collections.synchronizedList(new ArrayList<>());
            double[] maxProbRef = new double[]{0.0};
            double[] sumProbRef = new double[]{0.0};
            int[] countedRef = new int[]{0};
            String[] dominantFakeTypeRef = new String[]{null};

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                final int idx = i;
                File frameFile = frames.get(idx);
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    File cropFile = new File(workDir, "face_" + idx + ".png");
                    File toSend;
                    try {
                        boolean hasFace = faceCropService.cropLargestFace(
                                frameFile, cropFile, pre.getFacePaddingRatio());
                        toSend = hasFace ? cropFile : frameFile;
                    } catch (Exception e) {
                        log.warn("第 {} 帧人脸裁剪异常，改用整帧: {}", idx, e.getMessage());
                        toSend = frameFile;
                    }

                    try {
                        VideoImageInferenceResponse inf = callImageInference(toSend);
                        double p = clamp01(inf.resolveFakeProbability());
                        VideoDetectionResult.FrameAnalysis fa = new VideoDetectionResult.FrameAnalysis();
                        fa.setFrameIndex(idx);
                        fa.setFakeProbability(p);
                        analyses.add(fa);
                        synchronized (VideoImagePipelineService.this) {
                            maxProbRef[0] = Math.max(maxProbRef[0], p);
                            sumProbRef[0] += p;
                            countedRef[0]++;
                            if (dominantFakeTypeRef[0] == null && inf.getFakeType() != null && !inf.getFakeType().isBlank()) {
                                dominantFakeTypeRef[0] = inf.getFakeType();
                            }
                        }
                    } catch (Exception e) {
                        log.warn("第 {} 帧模型调用失败: {}", idx, e.getMessage());
                    }
                }, videoFrameExecutor);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int counted = countedRef[0];
            double maxProb = maxProbRef[0];
            double sumProb = sumProbRef[0];
            String dominantFakeType = dominantFakeTypeRef[0];

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

        // Flask 返回统一格式: {"code":0,"message":"success","data":{"fake_probability":0.95,...}}
        // 兼容旧格式: {"success":true,"data":{"fake_probability":0.95,...}}
        // 先解析为 Map 再手动提取 data 字段
        ResponseEntity<Map> resp = restTemplate.exchange(
                resolveImageUrl(),
                HttpMethod.POST,
                entity,
                Map.class
        );
        Map<String, Object> raw = resp.getBody();
        if (raw == null) {
            throw new RuntimeException("Flask 返回空 body");
        }

        Object dataObj = raw.get("data");
        if (dataObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) dataObj;
            VideoImageInferenceResponse r = new VideoImageInferenceResponse();
            Object fp = data.get("fake_probability");
            if (fp instanceof Number) r.setFakeProbability(((Number) fp).doubleValue());
            Object ft = data.get("fake_type");
            if (ft instanceof String) r.setFakeType((String) ft);
            Object conf = data.get("confidence");
            // Flask 的 confidence 可能在顶层或 data 内
            if (conf instanceof Number) r.setConfidence(((Number) conf).doubleValue());
            // 如果 data 内没有 confidence，从顶层的 is_fake 推断
            if (r.getConfidence() == null && data.get("is_fake") != null) {
                Object isFake = data.get("is_fake");
                Object fpVal = data.get("fake_probability");
                if (fpVal instanceof Number) {
                    double p = ((Number) fpVal).doubleValue();
                    r.setConfidence(Math.abs(p - 0.5) * 2);
                }
            }
            return r;
        }

        throw new RuntimeException("Flask 返回格式异常: data 字段缺失或非 Map");
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
