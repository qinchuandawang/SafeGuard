package com.sdu.safeguard.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class RestTemplateConfig {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int HEALTH_CHECK_TIMEOUT_MS = 2000;
    private static final int MAX_RETRIES = 2;
    private static final long BASE_DELAY_MS = 500;

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

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setInterceptors(List.of(new RetryInterceptor()));
        return restTemplate;
    }

    @Bean
    public RestTemplate healthCheckRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS));
        return new RestTemplate(factory);
    }

    private static class RetryInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            String uri = request.getURI().getPath();
            if (uri.contains("stream") || uri.contains("sse")) {
                return execution.execute(request, body);
            }

            IOException lastException = null;
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    ClientHttpResponse response = execution.execute(request, body);
                    int status = response.getStatusCode().value();
                    if ((status == 429 || status >= 500) && attempt < MAX_RETRIES) {
                        response.close();
                        long delay = BASE_DELAY_MS * (1L << attempt);
                        sleepUnchecked(delay);
                        continue;
                    }
                    return response;
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < MAX_RETRIES) {
                        long delay = BASE_DELAY_MS * (1L << attempt);
                        sleepUnchecked(delay);
                    }
                }
            }
            throw lastException != null ? lastException : new IOException("重试耗尽");
        }

        private static void sleepUnchecked(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
