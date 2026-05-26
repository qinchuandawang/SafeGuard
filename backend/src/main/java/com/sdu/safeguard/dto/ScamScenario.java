package com.sdu.safeguard.dto;

import lombok.Getter;

@Getter
public enum ScamScenario {
    IMPERSONATE_RELATIVE("冒充熟人",
            "你是一名诈骗者，冒充受害者的亲戚，声称遇到紧急情况需要借钱。"),
    IMPERSONATE_POLICE("冒充公检法",
            "你是一名诈骗者，冒充警察/检察官，声称受害者涉及案件需要配合调查。"),
    BRUSH_ORDER("刷单诈骗",
            "你是一名诈骗者，以高薪兼职为诱饵，引导受害者进行刷单任务。"),
    INVESTMENT("投资诈骗",
            "你是一名诈骗者，以高回报投资为诱饵，引导受害者投入资金。"),
    CUSTOMER_SERVICE("冒充客服",
            "你是一名诈骗者，冒充电商平台客服，声称订单有问题需要退款。");

    private final String name;
    private final String systemPrompt;

    ScamScenario(String name, String systemPrompt) {
        this.name = name;
        this.systemPrompt = systemPrompt;
    }

    public static ScamScenario resolve(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return IMPERSONATE_RELATIVE;
        }
        return switch (scriptId.toLowerCase()) {
            case "police" -> IMPERSONATE_POLICE;
            case "relative" -> IMPERSONATE_RELATIVE;
            case "investment" -> INVESTMENT;
            case "online" -> CUSTOMER_SERVICE;
            case "brush_order" -> BRUSH_ORDER;
            default -> IMPERSONATE_RELATIVE;
        };
    }
}
