package com.sdu.safeguard.controller;

import com.sdu.safeguard.dto.AdminLoginRequest;
import com.sdu.safeguard.dto.AdminRegisterRequest;
import com.sdu.safeguard.dto.LoginRequest;
import com.sdu.safeguard.dto.LoginResponse;
import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.entity.User;
import com.sdu.safeguard.service.UserService;
import com.sdu.safeguard.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final com.sdu.safeguard.mapper.UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @PostMapping("/admin/login")
    public Result<LoginResponse> adminLogin(@RequestBody AdminLoginRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            return Result.badRequest("用户名和密码不能为空");
        }
        String username = request.getUsername().trim();
        // 必须按用户名对应的 openid 精确查找，禁止"取任意 admin"——否则多个 admin 时密码可互换
        String openid = "admin_" + username;
        User admin = userMapper.findByOpenid(openid);
        if (admin == null) {
            return Result.error("用户名或密码错误");
        }
        if (admin.getPasswordHash() == null || admin.getPasswordHash().isBlank()) {
            return Result.error("管理员密码未初始化，请联系系统管理员");
        }
        if (!passwordEncoder.matches(request.getPassword(), admin.getPasswordHash())) {
            return Result.error("用户名或密码错误");
        }
        String token = jwtUtil.generateToken(admin.getId(), admin.getOpenid(), admin.getRole());
        return Result.success(LoginResponse.builder()
                .token(token)
                .userId(admin.getId())
                .role(admin.getRole())
                .nickname(admin.getNickname())
                .build());
    }

    /**
     * 注册新管理员账号
     * 注：仅在没有管理员账号时启用，或在受控环境下使用。
     * 真实生产环境应限制为内部邀请/审核流程。
     */
    @PostMapping("/admin/register")
    public Result<LoginResponse> adminRegister(@RequestBody AdminRegisterRequest request) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            return Result.error("用户名不能为空");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            return Result.error("密码长度至少 6 位");
        }
        if (request.getNickname() == null || request.getNickname().trim().isEmpty()) {
            return Result.error("昵称不能为空");
        }
        String openid = "admin_" + request.getUsername().trim();
        if (userMapper.findByOpenidAny(openid) != null) {
            return Result.error("用户名已存在");
        }
        User admin = User.builder()
                .openid(openid)
                .nickname(request.getNickname().trim())
                .role("admin")
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .lastLoginAt(LocalDateTime.now())
                .build();
        userMapper.insert(admin);
        log.info("新管理员注册成功: username={}, id={}", request.getUsername(), admin.getId());
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
                    response.getUserId(), response.getRole(), response.getIsNewUser());
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

    /**
     * 当前管理员的登录历史
     * 注：当前实现是从 user.lastLoginAt 派生，未来接入登录日志表后再扩展。
     */
    @GetMapping("/admin/login-history")
    public Result<List<Map<String, Object>>> adminLoginHistory(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return Result.error("未登录");
        }
        if (!jwtUtil.validateToken(token)) {
            return Result.error("token已过期或无效");
        }
        User admin = userMapper.selectById(jwtUtil.getUserId(token));
        if (admin == null) {
            return Result.error("用户不存在");
        }
        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ip", "登录设备");
        entry.put("location", "本机");
        entry.put("device", "当前会话");
        entry.put("loginAt", admin.getLastLoginAt() != null
                ? admin.getLastLoginAt().toString().replace('T', ' ')
                : null);
        history.add(entry);
        return Result.success(history);
    }

    /**
     * 管理员更新个人资料（昵称/邮箱/手机/部门/简介）
     */
    @PutMapping("/admin/profile")
    public Result<User> updateAdminProfile(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) return Result.error("未登录");
        if (!jwtUtil.validateToken(token)) return Result.error("token已过期或无效");
        Long userId = jwtUtil.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) return Result.error("用户不存在");
        if (body.containsKey("nickname")) user.setNickname(String.valueOf(body.get("nickname")));
        if (body.containsKey("email")) user.setEmail(String.valueOf(body.get("email")));
        if (body.containsKey("phone")) user.setPhone(String.valueOf(body.get("phone")));
        if (body.containsKey("department")) user.setDepartment(String.valueOf(body.get("department")));
        if (body.containsKey("bio")) user.setBio(String.valueOf(body.get("bio")));
        userMapper.updateById(user);
        // 不返回密码哈希
        user.setPasswordHash(null);
        return Result.success(user);
    }

    /**
     * 管理员修改密码
     */
    @PutMapping("/admin/password")
    public Result<Void> changeAdminPassword(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) return Result.error("未登录");
        if (!jwtUtil.validateToken(token)) return Result.error("token已过期或无效");
        String oldPwd = body.get("oldPassword") == null ? null : String.valueOf(body.get("oldPassword"));
        String newPwd = body.get("newPassword") == null ? null : String.valueOf(body.get("newPassword"));
        if (oldPwd == null || oldPwd.isBlank() || newPwd == null || newPwd.length() < 6) {
            return Result.error("原密码不能为空，新密码至少 6 位");
        }
        Long userId = jwtUtil.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) return Result.error("用户不存在");
        if (user.getPasswordHash() == null || !passwordEncoder.matches(oldPwd, user.getPasswordHash())) {
            return Result.error("原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPwd));
        userMapper.updateById(user);
        return Result.success(null);
    }

    /**
     * 管理员上传头像（保存为 base64 dataURL）
     * 注：仅持久化到 user.avatar_url 字段；如需文件存储可扩展为对象存储。
     */
    @PostMapping("/admin/avatar")
    public Result<User> uploadAdminAvatar(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) return Result.error("未登录");
        if (!jwtUtil.validateToken(token)) return Result.error("token已过期或无效");
        String dataUrl = body.get("file") == null ? null : String.valueOf(body.get("file"));
        // 兼容旧字段名
        if (dataUrl == null && body.get("avatar") != null) dataUrl = String.valueOf(body.get("avatar"));
        if (dataUrl == null || dataUrl.isBlank()) return Result.error("头像数据不能为空");
        if (dataUrl.length() > 500 * 1024) return Result.error("头像过大，请压缩后再上传");
        Long userId = jwtUtil.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) return Result.error("用户不存在");
        user.setAvatarUrl(dataUrl);
        userMapper.updateById(user);
        user.setPasswordHash(null);
        return Result.success(user);
    }

    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }
}
