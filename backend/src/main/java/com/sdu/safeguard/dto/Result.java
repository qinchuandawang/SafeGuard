package com.sdu.safeguard.dto;

import lombok.Data;

@Data
public class Result<T> {
    /** 业务码：200 成功；400 客户端参数错误；429 频率限制；500 服务器错误 */
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.setCode(500);
        result.setMessage(message);
        return result;
    }

    /**
     * 业务参数校验失败（语义 = HTTP 400）。
     * 用于把"用户输入不合法"与"服务器内部错误"区分开，前端可按 code 弹不同提示。
     */
    public static <T> Result<T> badRequest(String message) {
        Result<T> result = new Result<>();
        result.setCode(400);
        result.setMessage(message);
        return result;
    }
}
