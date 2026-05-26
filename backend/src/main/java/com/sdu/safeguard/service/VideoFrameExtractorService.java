package com.sdu.safeguard.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class VideoFrameExtractorService {

    public List<File> extractFrames(File videoFile, File workDir, double sampleIntervalSeconds, int maxFrames)
            throws IOException {
        if (!videoFile.isFile()) {
            throw new IOException("视频文件不存在: " + videoFile.getAbsolutePath());
        }
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + workDir.getAbsolutePath());
        }

        long intervalMicros = Math.max(1L, (long) (sampleIntervalSeconds * 1_000_000L));
        List<File> out = new ArrayList<>();
        Java2DFrameConverter converter = new Java2DFrameConverter();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            grabber.start();
            for (int i = 0; i < maxFrames; i++) {
                grabber.setTimestamp(i * intervalMicros);
                Frame frame = grabber.grabImage();
                if (frame == null || frame.image == null) {
                    break;
                }
                BufferedImage bi = converter.getBufferedImage(frame);
                if (bi == null) {
                    continue;
                }
                File png = new File(workDir, "frame_" + i + "_" + UUID.randomUUID() + ".png");
                if (!ImageIO.write(bi, "png", png)) {
                    throw new IOException("帧图片写入失败: " + png.getAbsolutePath());
                }
                out.add(png);
            }
            grabber.stop();
        } catch (Exception e) {
            throw new IOException("视频抽帧失败: " + e.getMessage(), e);
        }

        if (out.isEmpty()) {
            throw new IOException("未能从视频中抽取有效帧，请检查格式或依赖是否完整");
        }
        log.info("视频抽帧完成，共 {} 帧，目录 {}", out.size(), workDir.getAbsolutePath());
        return out;
    }
}
