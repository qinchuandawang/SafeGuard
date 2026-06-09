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
            if (path.startsWith(entry.getKey())) {
                capacity = entry.getValue();
                break;
            }
        }

        // 登录端点单独收紧：5 次/分钟/IP，防止暴力破解。
        // 之前整个 /api/auth/ 前缀都被白名单，登录可被无限制穷举。
        if (path.equals("/api/auth/admin/login") || path.equals("/api/auth/admin/register")
                || path.equals("/api/auth/login")) {
            capacity = 5;
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
        // 注意：/api/auth/admin/login 与 /api/auth/login 仍然会被 RateLimitInterceptor 拦截限流
        // （见 preHandle 中的特殊分支），此处只把"非登录类认证端点"放进白名单
        if (path.startsWith("/admin/")) return true;
        if (path.equals("/api/auth/userinfo") || path.equals("/api/auth/admin/profile")
                || path.equals("/api/auth/admin/login-history")
                || path.equals("/api/auth/admin/avatar") || path.equals("/api/auth/admin/password")) {
            return true;
        }
        return path.startsWith("/api/health") || path.startsWith("/actuator/")
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
