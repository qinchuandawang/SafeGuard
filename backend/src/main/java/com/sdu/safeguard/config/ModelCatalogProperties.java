package com.sdu.safeguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "model-catalog")
public class ModelCatalogProperties {

    private String defaultAudioModel = "wav2vec2-asvspoof";
    private String defaultVideoModel = "xception-ffpp";
    private List<ModelSpec> audio = new ArrayList<>();
    private List<ModelSpec> video = new ArrayList<>();

    @Data
    public static class ModelSpec {
        private String id;
        private String name;
        private String family;
        private String dataset;
        private String path;
        private Double accuracy;
        private Double eer;
        private String description;
        private boolean enabled;
    }
}
