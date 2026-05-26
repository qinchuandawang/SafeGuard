package com.sdu.safeguard.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdu.safeguard.config.WeChatConfig;
import com.sdu.safeguard.dto.LoginResponse;
import com.sdu.safeguard.entity.User;
import com.sdu.safeguard.mapper.UserMapper;
import com.sdu.safeguard.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final WeChatConfig weChatConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public LoginResponse login(String code, String nickname, String avatarUrl) {
        String openid;
        try {
            openid = code2openid(code);
        } catch (Exception e) {
            log.warn("微信登录失败, 使用测试模式: {}", e.getMessage());
            openid = "test_" + code.hashCode();
        }
        if (openid == null || openid.isBlank()) {
            openid = "test_" + System.currentTimeMillis();
        }

        User user = userMapper.findByOpenid(openid);
        boolean isNewUser = false;

        if (user == null) {
            user = User.builder()
                    .openid(openid)
                    .nickname(nickname != null ? nickname : "用户")
                    .avatarUrl(avatarUrl)
                    .role("user")
                    .lastLoginAt(LocalDateTime.now())
                    .build();
            userMapper.insert(user);
            isNewUser = true;
        } else {
            if (nickname != null) user.setNickname(nickname);
            if (avatarUrl != null) user.setAvatarUrl(avatarUrl);
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(user);
        }

        String token = jwtUtil.generateToken(user.getId(), openid, user.getRole());

        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .role(user.getRole())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .isNewUser(isNewUser)
                .build();
    }

    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    private String code2openid(String code) {
        if (weChatConfig.getAppid() == null || weChatConfig.getAppid().isBlank()
                || weChatConfig.getSecret() == null || weChatConfig.getSecret().isBlank()) {
            log.warn("微信配置不完整，使用测试openid");
            return "test_openid_" + code.hashCode();
        }
        String url = String.format("%s?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                weChatConfig.getLoginUrl(), weChatConfig.getAppid(), weChatConfig.getSecret(), code);
        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode json = objectMapper.readTree(response);
            if (json.has("openid")) {
                return json.get("openid").asText();
            }
            log.error("微信登录失败: {}", response);
            throw new RuntimeException("微信登录失败: " + json.get("errmsg").asText());
        } catch (Exception e) {
            log.error("调用微信登录接口失败", e);
            throw new RuntimeException("微信登录服务调用失败", e);
        }
    }
}
