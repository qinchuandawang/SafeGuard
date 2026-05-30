package com.sdu.safeguard.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 确保所有 JSON 响应包含 charset=UTF-8，解决 Windows 中文环境下
 * Jackson 不输出编码导致前端收到 GBK 乱码的问题。
 */
@Configuration
public class EncodingFilterConfig {

    @Bean
    public FilterRegistrationBean<Filter> utf8EncodingFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter((request, response, chain) -> {
            chain.doFilter(request, new CharsetResponseWrapper((HttpServletResponse) response));
        });
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }

    private static class CharsetResponseWrapper extends HttpServletResponseWrapper {

        CharsetResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setContentType(String type) {
            if (type != null && type.startsWith("application/json") && !type.contains("charset")) {
                super.setContentType(type + ";charset=UTF-8");
            } else {
                super.setContentType(type);
            }
        }

        @Override
        public void setHeader(String name, String value) {
            if (name != null && value != null
                    && "Content-Type".equalsIgnoreCase(name)
                    && value.startsWith("application/json")
                    && !value.contains("charset")) {
                super.setHeader(name, value + ";charset=UTF-8");
            } else {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (name != null && value != null
                    && "Content-Type".equalsIgnoreCase(name)
                    && value.startsWith("application/json")
                    && !value.contains("charset")) {
                super.addHeader(name, value + ";charset=UTF-8");
            } else {
                super.addHeader(name, value);
            }
        }
    }
}