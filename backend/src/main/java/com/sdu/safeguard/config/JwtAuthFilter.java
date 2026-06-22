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

@Slf4j
@Configuration
@RequiredArgsConstructor
public class JwtAuthFilter {

    private final JwtUtil jwtUtil;

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

            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String token = auth.substring(7);
                if (jwtUtil.validateToken(token)) {
                    String role = jwtUtil.getRole(token);
                    request.setAttribute("currentUserId", jwtUtil.getUserId(token));
                    request.setAttribute("currentOpenid", jwtUtil.getOpenid(token));
                    request.setAttribute("currentRole", role);
                } else {
                    log.debug("演示模式忽略无效 token: {}", path);
                }
            } else {
                applyDemoIdentity(request);
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
}
