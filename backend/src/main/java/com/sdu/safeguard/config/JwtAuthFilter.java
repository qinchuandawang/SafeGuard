package com.sdu.safeguard.config;

import com.sdu.safeguard.util.JwtUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class JwtAuthFilter {

    private final JwtUtil jwtUtil;

    /** 无需认证的公开路径。应仅包含登录、注册、健康检查、错误页 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/admin/login",
            "/api/auth/admin/register",
            "/api/health",
            "/actuator/health",
            "/error"
    );

    @Bean
    public FilterRegistrationBean<Filter> authFilterRegistration() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter((servletRequest, servletResponse, chain) -> {
            HttpServletRequest request = (HttpServletRequest) servletRequest;
            HttpServletResponse response = (HttpServletResponse) servletResponse;
            String path = request.getRequestURI();

            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }

            if (isPublicPath(path)) {
                chain.doFilter(request, response);
                return;
            }

            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String token = auth.substring(7);
                if (jwtUtil.validateToken(token)) {
                    chain.doFilter(request, response);
                    return;
                }
            }

            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或token已过期\",\"data\":null}");
        });
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(p ->
                path.equals(p) || path.startsWith(p + "/") || path.startsWith(p + "?"));
    }
}
