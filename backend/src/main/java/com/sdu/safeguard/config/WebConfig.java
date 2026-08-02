package com.sdu.safeguard.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isBlank())
                        .toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .order(0);
    }

    /**
     * 修复微信小程序 wx.request / wx.uploadFile 在 Windows 下按 GBK 解码 UTF-8 响应导致中文乱码:
     * 1) 启用 JsonWriteFeature.ESCAPE_NON_ASCII: 把所有非 ASCII 字符序列化为 backslash-u XXXX 形式,
     *    JSON 响应体只含 ASCII, 客户端无论按什么编码解码都能得到正确字符串(JSON.parse 会自动反转义)。
     * 2) 强制 defaultCharset = UTF-8: 确保响应 Content-Type 包含 charset=UTF-8, 遵循 RFC 7159。
     *
     * 这里用 configureMessageConverters 替换默认 MappingJackson2HttpMessageConverter,
     * Spring 会按 supportedMediaTypes 匹配 application/json。
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        JsonMapper mapper = JsonMapper.builder()
                .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
        MappingJackson2HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter(mapper);
        jacksonConverter.setDefaultCharset(StandardCharsets.UTF_8);
        jacksonConverter.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                new MediaType("application", "*+json", StandardCharsets.UTF_8)
        ));
        // 放到最前, 让 Spring MVC 优先使用我们的转换器
        converters.add(0, jacksonConverter);
    }
}
