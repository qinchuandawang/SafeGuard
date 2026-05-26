package com.sdu.safeguard.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@Slf4j
@ConfigurationProperties(prefix = "video")
public class VideoProperties {

    private Service service = new Service();
    private Preprocess preprocess = new Preprocess();

    @Data
    public static class Service {
        private String url = "";
        private String imageUrl = "";
    }

    @Data
    public static class Preprocess {
        private boolean enabled = true;
        private double sampleIntervalSeconds = 1.0;
        private int maxFrames = 24;
        private double facePaddingRatio = 0.12;
        private String multipartField = "file";
    }

    @PostConstruct
    public void validate() {
        if (preprocess != null && preprocess.isEnabled()) {
            if (service == null || service.getUrl() == null || service.getUrl().isBlank()) {
                log.warn("视频预处理已启用但 video.service.url 未配置");
            }
        }
        if (preprocess != null && preprocess.getSampleIntervalSeconds() <= 0) {
            log.warn("视频抽帧间隔必须大于0，当前值: {}", preprocess.getSampleIntervalSeconds());
            preprocess.setSampleIntervalSeconds(1.0);
        }
        if (preprocess != null && preprocess.getMaxFrames() <= 0) {
            log.warn("最大帧数必须大于0，当前值: {}", preprocess.getMaxFrames());
            preprocess.setMaxFrames(24);
        }
    }
}
