package com.sdu.safeguard.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;

@Slf4j
@Service
public class FaceCropService {

    private CascadeClassifier faceClassifier;

    @PostConstruct
    public void init() {
        try {
            Loader.load(org.bytedeco.opencv.global.opencv_objdetect.class);
            Path cascadePath = extractBundledCascade();
            if (cascadePath == null) {
                log.warn("未找到人脸级联文件，将使用整帧送检");
                faceClassifier = null;
                return;
            }
            faceClassifier = new CascadeClassifier(cascadePath.toAbsolutePath().toString());
            if (faceClassifier == null || faceClassifier.empty()) {
                log.warn("级联分类器为空，人脸步骤将跳过，直接使用整帧");
                faceClassifier = null;
            }
        } catch (Throwable t) {
            log.warn("OpenCV 人脸级联加载失败，将使用整帧送检: {}", t.getMessage());
            faceClassifier = null;
        }
    }

    private static Path extractBundledCascade() {
        try (InputStream in = FaceCropService.class.getResourceAsStream("/opencv/lbpcascade_frontalface.xml")) {
            if (in == null) {
                return null;
            }
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "safe_guard", "opencv");
            Files.createDirectories(dir);
            Path out = dir.resolve("lbpcascade_frontalface.xml");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean cropLargestFace(File framePng, File cropPng, double facePaddingRatio) throws IOException {
        // 人脸检测是可选预处理步骤，失败时由上层回退为整帧送检。
        if (faceClassifier == null) {
            return false;
        }

        Mat bgr = imread(framePng.getAbsolutePath(), IMREAD_COLOR);
        if (bgr == null || bgr.empty()) {
            return false;
        }

        Mat gray = new Mat();
        cvtColor(bgr, gray, COLOR_BGR2GRAY);
        equalizeHist(gray, gray);

        RectVector faces = new RectVector();
        faceClassifier.detectMultiScale(gray, faces);
        if (faces.size() == 0) {
            return false;
        }

        Rect best = faces.get(0);
        int area = best.width() * best.height();
        for (long i = 1; i < faces.size(); i++) {
            Rect r = faces.get(i);
            int a = r.width() * r.height();
            if (a > area) {
                best = r;
                area = a;
            }
        }

        int padX = (int) (best.width() * facePaddingRatio);
        int padY = (int) (best.height() * facePaddingRatio);
        int x1 = Math.max(0, best.x() - padX);
        int y1 = Math.max(0, best.y() - padY);
        int x2 = Math.min(bgr.cols(), best.x() + best.width() + padX);
        int y2 = Math.min(bgr.rows(), best.y() + best.height() + padY);
        if (x2 <= x1 || y2 <= y1) {
            return false;
        }

        Rect roi = new Rect(x1, y1, x2 - x1, y2 - y1);
        Mat cropped = new Mat(bgr, roi);
        return imwrite(cropPng.getAbsolutePath(), cropped);
    }
}
