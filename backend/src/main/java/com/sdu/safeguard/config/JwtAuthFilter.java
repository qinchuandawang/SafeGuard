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
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class JwtAuthFilter {

    private final JwtUtil jwtUtil;

    @Value("${app.auth.demo-identity-enabled:true}")
    private boolean demoIdentityEnabled;

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

            String token = resolveToken(request);
            if (token != null) {
                if (jwtUtil.validateToken(token)) {
                    String role = jwtUtil.getRole(token);
                    request.setAttribute("currentUserId", jwtUtil.getUserId(token));
                    request.setAttribute("currentOpenid", jwtUtil.getOpenid(token));
                    request.setAttribute("currentRole", role);
                } else if (requiresAdmin(path)) {
                    reject(response, HttpServletResponse.SC_UNAUTHORIZED, "登录凭证无效");
                    return;
                }
            } else if (demoIdentityEnabled) {
                applyDemoIdentity(request);
            } else if (requiresAdmin(path)) {
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
                return;
            }

            if (requiresAdmin(path) && !"admin".equals(request.getAttribute("currentRole"))) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
                return;
            }

            chain.doFilter(request, response);
        });
        registration.addUrlPatterns("/api/*", "/admin/*");
        registration.setOrder(1);
        return registration;
    }

    private void applyDemoIdentity(HttpServletRequest request) {
        request.setAttribute("currentUserId", 1L);
        request.setAttribute("currentOpenid", "demo_openid");
        request.setAttribute("currentRole", "admin");
    }

    private String resolveToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        Object sessionToken = session == null ? null : session.getAttribute("admin_token");
        return sessionToken instanceof String value && !value.isBlank() ? value : null;
    }

    private boolean requiresAdmin(String path) {
        return path.startsWith("/api/admin/") || path.equals("/api/admin")
                || (path.startsWith("/admin/") && !path.equals("/admin/login"));
    }

    private void reject(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
