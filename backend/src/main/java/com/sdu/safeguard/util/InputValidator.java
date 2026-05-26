package com.sdu.safeguard.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 输入校验工具类，防止过长的输入或包含控制字符的输入进入 LLM/数据库搜索。
 */
@Slf4j
public final class InputValidator {

    private static final int MAX_TEXT_LENGTH = 5000;
    private static final int MAX_KEYWORD_LENGTH = 200;
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private InputValidator() {}

    /**
     * 校验用于 LLM 分析的文本
     * @return 若校验失败则返回错误描述，成功返回 null
     */
    public static String validateAnalysisText(String text) {
        if (text == null || text.isBlank()) {
            return "输入文本不能为空";
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return "输入文本过长，上限 " + MAX_TEXT_LENGTH + " 字符";
        }
        if (containsControlChars(text)) {
            return "输入包含不可见控制字符";
        }
        return null;
    }

    /**
     * 校验搜索关键词
     */
    public static String validateKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "搜索关键词不能为空";
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            return "关键词过长，上限 " + MAX_KEYWORD_LENGTH + " 字符";
        }
        return null;
    }

    /**
     * 校验对话消息
     */
    public static String validateMessage(String message) {
        if (message == null || message.isBlank()) {
            return "消息不能为空";
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return "消息过长，上限 " + MAX_MESSAGE_LENGTH + " 字符";
        }
        if (containsControlChars(message)) {
            return "消息包含不可见控制字符";
        }
        return null;
    }

    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 允许 \t \n \r，但禁止其他控制字符（U+0000-U+001F, U+007F-U+009F）
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                return true;
            }
            if (c >= 0x7F && c <= 0x9F) {
                return true;
            }
        }
        return false;
    }
}
