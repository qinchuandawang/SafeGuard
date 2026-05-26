package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.AdminLoginRequest;
import com.sdu.safeguard.dto.LoginRequest;
import com.sdu.safeguard.dto.LoginResponse;
import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.User;
import com.sdu.safeguard.service.UserService;
import com.sdu.safeguard.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final com.sdu.safeguard.mapper.UserMapper userMapper;

    @PostMapping("/admin/login")
    public Result<LoginResponse> adminLogin(@RequestBody AdminLoginRequest request) {
        if (request.getUsername() == null || request.getPassword() == null) {
            return Result.error("用户名和密码不能为空");
        }
        if (!"admin".equals(request.getUsername()) || !"admin123".equals(request.getPassword())) {
            return Result.error("用户名或密码错误");
        }
        com.sdu.safeguard.entity.User admin = userMapper.findAnyAdmin();
        if (admin == null) {
            return Result.error("管理员账号不存在");
        }
        String token = jwtUtil.generateToken(admin.getId(), admin.getOpenid(), admin.getRole());
        return Result.success(LoginResponse.builder()
                .token(token)
                .userId(admin.getId())
                .role(admin.getRole())
                .nickname(admin.getNickname())
                .build());
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        if (request.getCode() == null || request.getCode().isBlank()) {
            return Result.error("登录code不能为空");
        }
        try {
            LoginResponse response = userService.login(
                    request.getCode(),
                    request.getNickname(),
                    request.getAvatarUrl()
            );
            log.info("用户登录成功: userId={}, role={}, isNewUser={}",
                    response.getUserId(), response.getRole(), response.isNewUser());
            return Result.success(response);
        } catch (Exception e) {
            log.error("登录失败", e);
            return Result.error("登录失败: " + e.getMessage());
        }
    }

    @GetMapping("/userinfo")
    public Result<User> getUserInfo(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return Result.error("未登录");
        }
        if (!jwtUtil.validateToken(token)) {
            return Result.error("token已过期或无效");
        }
        try {
            Long userId = jwtUtil.getUserId(token);
            User user = userService.getById(userId);
            if (user == null) {
                return Result.error("用户不存在");
            }
            return Result.success(user);
        } catch (Exception e) {
            return Result.error("token无效: " + e.getMessage());
        }
    }

    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }
}
