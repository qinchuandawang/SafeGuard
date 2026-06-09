package com.sdu.safeguard.dto;

import lombok.Data;

@Data
public class AdminRegisterRequest {
    private String username;
    private String nickname;
    private String password;
}
