package com.sdu.safeguard.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    private String scriptId;
    private String message;
    private List<Message> history;
}
