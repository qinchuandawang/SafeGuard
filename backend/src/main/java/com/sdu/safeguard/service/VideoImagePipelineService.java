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

    private final RestTemplate videoRestTemplate;
    private final VideoProperties videoProperties;
    private final VideoFrameExtractorService videoFrameExtractorService;
    private final FaceCropService faceCropService;
    private final VideoMetadataEvidenceService videoMetadataEvidenceService;
    private final Executor videoFrameExecutor;

    public VideoImagePipelineService(@org.springframework.beans.factory.annotation.Qualifier("videoRestTemplate") RestTemplate videoRestTemplate,
                                     VideoProperties videoProperties,
                                     VideoFrameExtractorService videoFrameExtractorService,
                                     FaceCropService faceCropService,
                                     VideoMetadataEvidenceService videoMetadataEvidenceService,
                                     @org.springframework.beans.factory.annotation.Qualifier("videoFrameExecutor") Executor videoFrameExecutor) {
        this.videoRestTemplate = videoRestTemplate;
        this.videoProperties = videoProperties;
        this.videoFrameExtractorService = videoFrameExtractorService;
        this.faceCropService = faceCropService;
        this.videoMetadataEvidenceService = videoMetadataEvidenceService;
        this.videoFrameExecutor = videoFrameExecutor;
    }

    public VideoDetectionResult detectFromVideoFile(String videoPath) {
        return detectFromVideoFile(videoPath, null, null);
    }

    public VideoDetectionResult detectFromVideoFile(String videoPath,
                                                     BiConsumer<Integer, Map<String, Object>> progressCallback) {
        return detectFromVideoFile(videoPath, progressCallback, null);
    }

    public VideoDetectionResult detectFromVideoFile(String videoPath,
                                                     BiConsumer<Integer, Map<String, Object>> progressCallback,
                                                     String modelId) {
        File videoFile = new File(videoPath);
        File workDir = new File(TEMP_ROOT, "pipeline_" + UUID.randomUUID());
        try {
            if (!workDir.mkdirs() && !workDir.isDirectory()) {
                throw new RuntimeException("无法创建预处理目录: " + workDir.getAbsolutePath());
            }

            var pre = videoProperties.getPreprocess();
            log.info("视频检测进度: 开始预处理 file={}, size={} bytes, maxFrames={}, sampleInterval={}s",
                    videoFile.getName(), videoFile.length(), pre.getMaxFrames(), pre.getSampleIntervalSeconds());
            if (progressCallback != null) {
                progressCallback.accept(5, Map.of("message", "正在提取视频帧..."));
            }
            List<File> frames = videoFrameExtractorService.extractFrames(
                    videoFile,
                    workDir,
                    pre.getSampleIntervalSeconds(),
                    pre.getMaxFrames()
            );
            log.info("视频检测进度: 抽帧完成 file={}, frames={}, workDir={}",
                    videoFile.getName(), frames.size(), workDir.getAbsolutePath());
            if (progressCallback != null) {
                progressCallback.accept(10, Map.of("message", "帧提取完成，共 " + frames.size() + " 帧，开始 AI 检测..."));
            }

            List<VideoDetectionResult.FrameAnalysis> analyses = Collections.synchronizedList(new ArrayList<>());
            double[] maxProbRef = new double[]{0.0};
            double[] sumProbRef = new double[]{0.0};
            int[] countedRef = new int[]{0};
            int[] suspiciousRef = new int[]{0};
            String[] dominantFakeTypeRef = new String[]{null};

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                final int idx = i;
                final int totalFrames = frames.size();
                if (progressCallback != null) {
                    int pct = 10 + (i * 70 / totalFrames); // 10% -> 80%
                    progressCallback.accept(pct, Map.of("message", "正在检测第 " + (i + 1) + "/" + totalFrames + " 帧..."));
                }
                log.info("视频检测进度: 提交第 {}/{} 帧推理", idx + 1, totalFrames);
                File frameFile = frames.get(idx);
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    File cropFile = new File(workDir, "face_" + idx + ".png");
                    File toSend;
                    boolean hasFace = false;
                    try {
                        hasFace = faceCropService.cropLargestFace(
                                frameFile, cropFile, pre.getFacePaddingRatio());
                        toSend = hasFace ? cropFile : frameFile;
                    } catch (Exception e) {
                        log.warn("第 {} 帧人脸裁剪异常，改用整帧: {}", idx, e.getMessage());
                        toSend = frameFile;
                    }

                    try {
                        VideoImageInferenceResponse inf = callImageInference(toSend, modelId);
                        double p = clamp01(inf.resolveFakeProbability());
                        String riskLabel = p >= 0.75 ? "高可疑" : (p >= 0.5 ? "可疑" : "低可疑");
                        VideoDetectionResult.FrameAnalysis fa = new VideoDetectionResult.FrameAnalysis();
                        fa.setFrameIndex(idx);
                        fa.setFakeProbability(p);
                        analyses.add(fa);
                        synchronized (VideoImagePipelineService.this) {
                            maxProbRef[0] = Math.max(maxProbRef[0], p);
                            sumProbRef[0] += p;
                            countedRef[0]++;
                            if (p >= 0.5) {
                                suspiciousRef[0]++;
                            }
                            if (dominantFakeTypeRef[0] == null && inf.getFakeType() != null && !inf.getFakeType().isBlank()) {
                                dominantFakeTypeRef[0] = inf.getFakeType();
                            }
                        }
                        log.info("视频检测进度: 第 {}/{} 帧完成，伪造概率={}%，风险标签={}，裁剪人脸={}",
                                idx + 1, totalFrames, String.format("%.1f", p * 100), riskLabel, hasFace);
                    } catch (Exception e) {
                        log.warn("第 {} 帧模型调用失败: {}", idx, e.getMessage());
                    }
                }, videoFrameExecutor);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            if (progressCallback != null) {
                progressCallback.accept(85, Map.of("message", "帧检测完成，正在聚合分析结果..."));
            }

            int counted = countedRef[0];
            if (counted == 0) {
                throw new RuntimeException("所有帧均未获得有效推理结果，请检查 video.service.image-url 与 Flask 服务");
            }
            double maxProb = maxProbRef[0];
            double sumProb = sumProbRef[0];
            double avgProb = sumProb / counted;
            double suspiciousRatio = suspiciousRef[0] / (double) counted;
            String dominantFakeType = dominantFakeTypeRef[0];
            log.info("视频检测进度: 逐帧推理完成，有效帧={}, 平均伪造概率={}%，最高单帧={}%，可疑帧占比={}%",
                    counted,
                    String.format("%.1f", avgProb * 100),
                    String.format("%.1f", maxProb * 100),
                    String.format("%.1f", suspiciousRatio * 100));

            VideoDetectionResult merged = new VideoDetectionResult();
            merged.setType("video");
            merged.setAverageFakeProbability(avgProb);
            merged.setMaxFakeProbability(maxProb);
            merged.setSuspiciousFrameRatio(suspiciousRatio);
            double visualRisk = aggregateVideoRisk(avgProb, maxProb, suspiciousRatio);
            merged.setVisualFakeProbability(visualRisk);
            merged.setFakeProbability(visualRisk);
            merged.setConfidence(videoConfidence(avgProb, maxProb));
            merged.setDetermination(merged.getFakeProbability() >= 0.55 ? "fake" : "real");
            merged.setFakeType(dominantFakeType != null ? dominantFakeType : "face_frame_aggregated");
            merged.setTotalFrames(frames.size());
            merged.setTotalFaces(counted);
            merged.setFramesWithFace(counted);
            merged.computeProbabilities();
            analyses.sort(Comparator.comparing(VideoDetectionResult.FrameAnalysis::getFrameIndex));
            merged.setFrameAnalysis(analyses);
            log.info("视频检测进度: 开始读取元数据证据 file={}", videoFile.getName());
            videoMetadataEvidenceService.applyMetadataEvidence(merged, videoFile);
            log.info("视频检测进度: 聚合完成 file={}, determination={}, fakeType={}, fakeProbability={}%, metadataHit={}",
                    videoFile.getName(),
                    merged.getDetermination(),
                    merged.getFakeType(),
                    String.format("%.1f", merged.getFakeProbability() == null ? 0.0 : merged.getFakeProbability() * 100),
                    Boolean.TRUE.equals(merged.getAigcMetadataDetected()));
            return merged;
        } catch (RuntimeException e) {
            if (shouldFallbackToDirectVideo(e)) {
                log.warn("视频逐帧检测不可用，回退到整视频检测: {}", e.getMessage());
                return fallbackToDirectVideo(videoFile, modelId);
            }
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

    private VideoImageInferenceResponse callImageInference(File file, String modelId) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(videoProperties.getPreprocess().getMultipartField(), new FileSystemResource(file));
        if (modelId != null && !modelId.isBlank()) {
            body.add("model_id", modelId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        // Flask 返回统一格式: {"code":0,"message":"success","data":{"fake_probability":0.95,...}}
        // 兼容旧格式: {"success":true,"data":{"fake_probability":0.95,...}}
        // 先解析为 Map 再手动提取 data 字段
        ResponseEntity<Map> resp = videoRestTemplate.exchange(
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

    private static double aggregateVideoRisk(double avgProb, double maxProb, double suspiciousRatio) {
        double risk = Math.max(avgProb, maxProb * 0.82);
        if (maxProb >= 0.5) {
            risk = Math.max(risk, Math.min(0.68, maxProb + 0.05));
        } else if (maxProb >= 0.4) {
            risk = Math.max(risk, Math.min(0.52, maxProb + 0.04));
        }
        if (suspiciousRatio >= 0.25) {
            risk = Math.max(risk, 0.62 + Math.min(0.18, suspiciousRatio * 0.25));
        } else if (suspiciousRatio > 0) {
            risk = Math.max(risk, 0.45 + suspiciousRatio * 0.5);
        }
        return clamp01(risk);
    }

    private static double videoConfidence(double avgProb, double maxProb) {
        return clamp01(Math.max(0.35, Math.min(0.98,
                Math.max(Math.abs(avgProb - 0.5) * 2, Math.abs(maxProb - 0.5) * 1.4))));
    }

    private boolean shouldFallbackToDirectVideo(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("所有帧均未获得有效推理结果")
                || message.contains("Connection refused")
                || message.contains("Connect to")
                || message.contains("I/O error on POST request");
    }

    private VideoDetectionResult fallbackToDirectVideo(File videoFile, String modelId) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(videoFile));
        if (modelId != null && !modelId.isBlank()) {
            body.add("model_id", modelId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp = videoRestTemplate.exchange(
                videoProperties.getService().getUrl(),
                HttpMethod.POST,
                entity,
                Map.class
        );
        Map<String, Object> raw = resp.getBody();
        if (raw == null) {
            throw new RuntimeException("整视频检测返回空响应");
        }
        Object dataObj = raw.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            throw new RuntimeException("整视频检测返回格式异常");
        }

        VideoDetectionResult result = new VideoDetectionResult();
        result.setType("video");
        Object avgFakeProb = data.get("average_fake_probability");
        Object maxFakeProb = data.get("max_fake_probability");
        Object suspiciousFrameRatio = data.get("suspicious_frame_ratio");
        double avgProb = avgFakeProb instanceof Number ? ((Number) avgFakeProb).doubleValue() : 0.0;
        double maxProb = maxFakeProb instanceof Number ? ((Number) maxFakeProb).doubleValue() : avgProb;
        double suspiciousRatio = suspiciousFrameRatio instanceof Number ? ((Number) suspiciousFrameRatio).doubleValue() : 0.0;
        result.setAverageFakeProbability(clamp01(avgProb));
        result.setMaxFakeProbability(clamp01(maxProb));
        result.setSuspiciousFrameRatio(clamp01(suspiciousRatio));
        double visualRisk = aggregateVideoRisk(result.getAverageFakeProbability(), result.getMaxFakeProbability(), result.getSuspiciousFrameRatio());
        result.setVisualFakeProbability(visualRisk);
        result.setFakeProbability(visualRisk);
        result.setConfidence(videoConfidence(result.getAverageFakeProbability(), result.getMaxFakeProbability()));
        result.setDetermination(result.getFakeProbability() >= 0.55 ? "fake" : "real");
        result.setFakeType(Boolean.TRUE.equals(data.get("is_fake")) ? "video_fallback" : "none");
        result.computeProbabilities();
        videoMetadataEvidenceService.applyMetadataEvidence(result, videoFile);
        log.info("已回退到整视频检测接口，fakeProbability={}", result.getFakeProbability());
        return result;
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
