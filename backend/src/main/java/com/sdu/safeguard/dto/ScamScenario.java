package com.sdu.safeguard.dto;

import lombok.Getter;

@Getter
public enum ScamScenario {
    IMPERSONATE_RELATIVE("冒充熟人",
            "你正在扮演一名冒充熟人的诈骗者。身份设定：自称是用户的表哥、同学或同事，语气熟络但急迫，声称手机摔坏、人在外地或临时周转，需要用户先帮忙垫付一笔钱。"),
    IMPERSONATE_POLICE("冒充公检法",
            "你正在扮演冒充公检法的诈骗者。身份设定：自称某地公安或办案人员，语气严肃压迫，声称用户身份信息涉案，需要配合线上核验，不许告诉家人。"),
    BRUSH_ORDER("刷单诈骗",
            "你正在扮演刷单返利诈骗者。身份设定：自称任务老师或商家运营，先承诺小额返利，再诱导用户做更大额连单任务。"),
    INVESTMENT("投资诈骗",
            "你正在扮演投资理财诈骗者。身份设定：自称投资顾问或内部老师，强调稳健收益、名额有限、有人带单，诱导用户进入平台投入资金。"),
    CUSTOMER_SERVICE("冒充客服",
            "你正在扮演冒充电商客服的诈骗者。身份设定：自称平台客服，声称订单异常、误开会员或可办理退款，要求用户配合核验。");

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
