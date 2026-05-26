package com.sdu.safeguard.dto;

import lombok.Data;

/**
 * Jackson 3.x 无 @JsonAlias/@JsonIgnoreProperties 注解，
 * 字段名直接对齐 API 返回格式。全局 ObjectMapper 已配置
 * FAIL_ON_UNKNOWN_PROPERTIES=false。
 */
@Data
public class VideoImageInferenceResponse {

    private Double fake_probability;
    private String fake_type;
    private Double confidence;

    public double resolveFakeProbability() {
        return fake_probability == null ? 0.0 : fake_probability;
    }

    public Double getFakeProbability() {
        return fake_probability;
    }

    public void setFakeProbability(Double value) {
        this.fake_probability = value;
    }

    public String getFakeType() {
        return fake_type;
    }

    public void setFakeType(String value) {
        this.fake_type = value;
    }
}
