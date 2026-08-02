package com.sdu.safeguard.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier; 
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class RestTemplateConfig {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int VIDEO_READ_TIMEOUT_MS = 180000; // 3 分钟，视频 CPU 推理耗时较长
    private static final int HEALTH_CHECK_TIMEOUT_MS = 2000;

    @Bean
    public HttpClient httpClient() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(200);
        cm.setDefaultMaxPerRoute(50);

        ConnectionConfig connConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                .setSocketTimeout(Timeout.ofMilliseconds(READ_TIMEOUT_MS))
                .setTimeToLive(30, TimeUnit.SECONDS)
                .build();
        cm.setDefaultConnectionConfig(connConfig);

        RequestConfig reqConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(5000))
                .build();

        return HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(reqConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    @Bean
    public RestTemplate restTemplate(HttpClient httpClient) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);

        return new RestTemplate(factory);
    }

    @Bean
    public RestTemplate healthCheckRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS));
        return new RestTemplate(factory);
    }

    @Bean
    @Qualifier("videoRestTemplate")
    public RestTemplate videoRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(VIDEO_READ_TIMEOUT_MS));
        return new RestTemplate(factory);
    }

}
