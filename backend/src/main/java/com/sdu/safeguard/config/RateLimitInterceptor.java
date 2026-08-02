package com.sdu.safeguard.config;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final Cache<String, Bucket> buckets;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Value("${infra.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${app.http.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

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

    public RateLimitInterceptor(@Qualifier("rateLimitBuckets") Cache<String, Bucket> buckets,
                                ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.buckets = buckets;
        this.redisTemplateProvider = redisTemplateProvider;
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

        String bucketKey = clientIp + ":" + normalizeRoute(path);
        if (redisEnabled) {
            Boolean redisAllowed = tryRedisLimit(bucketKey, capacity);
            if (Boolean.TRUE.equals(redisAllowed)) {
                return true;
            }
            if (Boolean.FALSE.equals(redisAllowed)) {
                reject(response, clientIp, path, capacity);
                return false;
            }
        }
        final int cap = capacity;
        Bucket bucket = buckets.get(bucketKey, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.simple(cap, DEFAULT_PERIOD))
                        .build());

        if (bucket.tryConsume(1)) {
            return true;
        }

        reject(response, clientIp, path, capacity);
        return false;
    }

    private Boolean tryRedisLimit(String bucketKey, int capacity) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return null;
        }
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[2]); end; " +
                            "if n<=tonumber(ARGV[1]) then return 1 else return 0 end", Long.class);
            Long allowed = redisTemplate.execute(script,
                    List.of("safeguard:rate-limit:" + bucketKey), String.valueOf(capacity), "60000");
            return Long.valueOf(1L).equals(allowed);
        } catch (Exception exception) {
            log.debug("Redis 限流不可用，降级到本机令牌桶: {}", exception.getMessage());
            return null;
        }
    }

    private void reject(HttpServletResponse response, String clientIp, String path, int capacity) throws Exception {
        log.warn("请求频率过高: ip={}, path={}, limit={}/min", clientIp, path, capacity);
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
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
        String ip = trustForwardedHeaders ? request.getHeader("X-Forwarded-For") : null;
        if (trustForwardedHeaders && (ip == null || ip.isBlank())) ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip;
    }

    private String normalizeRoute(String path) {
        if (path == null) return "unknown";
        return path
                .replaceAll("/[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,36}(?=/|$)", "/{id}")
                .replaceAll("/[0-9a-fA-F]{32,64}(?=/|$)", "/{id}")
                .replaceAll("/\\d+(?=/|$)", "/{id}");
    }
}
