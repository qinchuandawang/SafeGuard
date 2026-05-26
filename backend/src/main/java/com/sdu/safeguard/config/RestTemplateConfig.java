package com.sdu.safeguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Configuration
public class RestTemplateConfig {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_RETRIES = 2;
    private static final long BASE_DELAY_MS = 500;

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setInterceptors(List.of(new RetryInterceptor()));
        return restTemplate;
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
