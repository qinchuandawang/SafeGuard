package com.sdu.safeguard.config;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final Cache<String, Bucket> buckets;

    private static final int DEFAULT_CAPACITY = 60;
    private static final Duration DEFAULT_PERIOD = Duration.ofMinutes(1);

    private static final Map<String, Integer> PATH_LIMITS = Map.of(
            "/api/llm/", 10,
            "/api/agent/", 15,
            "/api/detection/text", 20,
            "/api/detection/multi", 15,
            "/api/simulate/chat", 20,
            "/api/rag/", 30
    );

    public RateLimitInterceptor(@Qualifier("rateLimitBuckets") Cache<String, Bucket> buckets) {
        this.buckets = buckets;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (request == null) {
            return true;
        }
        String path = request.getRequestURI();
        String clientIp = getClientIp(request);

        if (isPublicPath(path)) {
            return true;
        }

        int capacity = DEFAULT_CAPACITY;

        for (Map.Entry<String, Integer> entry : PATH_LIMITS.entrySet()) {
            if (path.contains(entry.getKey())) {
                capacity = entry.getValue();
                break;
            }
        }

        String bucketKey = clientIp + ":" + path;
        final int cap = capacity;
        Bucket bucket = buckets.get(bucketKey, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.simple(cap, DEFAULT_PERIOD))
                        .build());

        if (bucket.tryConsume(1)) {
            return true;
        }

        log.warn("请求频率过高: ip={}, path={}, limit={}/min", clientIp, path, capacity);
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
        return false;
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/admin/") || path.startsWith("/api/auth")
                || path.startsWith("/api/health") || path.startsWith("/actuator")
                || path.equals("/error");
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip;
    }
}
