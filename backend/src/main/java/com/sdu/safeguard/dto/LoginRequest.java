package com.sdu.safeguard.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String code;
    private String nickname;
    private String avatarUrl;
}
