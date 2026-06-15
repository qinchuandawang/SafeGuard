package com.sdu.safeguard.config;

import com.sdu.safeguard.util.JwtUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
            "/admin/login",
            "/admin/css",
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

            if (path.startsWith("/admin/") && !isPublicPath(path)) {
                if (isValidAdminSession(request)) {
                    chain.doFilter(request, response);
                    return;
                }
                response.sendRedirect("/admin/login");
                return;
            }

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
                    String role = jwtUtil.getRole(token);
                    request.setAttribute("currentUserId", jwtUtil.getUserId(token));
                    request.setAttribute("currentOpenid", jwtUtil.getOpenid(token));
                    request.setAttribute("currentRole", role);
                    if (requiresAdmin(path, request.getMethod()) && !"admin".equals(role)) {
                        writeJson(response, 403, "{\"code\":403,\"message\":\"需要管理员权限\",\"data\":null}");
                        return;
                    }
                    chain.doFilter(request, response);
                    return;
                }
            }

            writeJson(response, 401, "{\"code\":401,\"message\":\"未登录或token已过期\",\"data\":null}");
        });
        registration.addUrlPatterns("/api/*", "/admin/*");
        registration.setOrder(1);
        return registration;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(p ->
                path.equals(p) || path.startsWith(p + "/") || path.startsWith(p + "?"));
    }

    private boolean isValidAdminSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object tokenObj = session.getAttribute("admin_token");
        if (!(tokenObj instanceof String token) || !jwtUtil.validateToken(token)) {
            return false;
        }
        return "admin".equals(jwtUtil.getRole(token));
    }

    private boolean requiresAdmin(String path, String method) {
        if (path.startsWith("/api/admin/")) {
            return true;
        }
        if (path.startsWith("/api/auth/admin/")) {
            return true;
        }
        if (path.startsWith("/api/records/stats/")) {
            return true;
        }
        if (path.startsWith("/api/audio/model")) {
            return true;
        }
        return path.startsWith("/api/knowledge")
                && ("POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method));
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }
}
