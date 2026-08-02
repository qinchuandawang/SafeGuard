package com.sdu.safeguard.config;

import com.sdu.safeguard.dto.Result;
import com.sdu.safeguard.service.ExternalDependencyBlockedException;
import com.sdu.safeguard.service.InferenceCapacityExceededException;
import com.sdu.safeguard.service.TokenBudgetExceededException;
import com.sdu.safeguard.service.TaskQueueFullException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("文件上传超过大小限制: {}", e.getMessage());
        return Result.error("文件大小超过限制，单个文件最大50MB，总请求最大100MB");
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMultipartException(MultipartException e) {
        log.warn("文件上传异常: {}", e.getMessage());
        return Result.error("文件上传失败，请检查文件格式");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return Result.error("缺少必填参数: " + e.getParameterName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return Result.error(e.getMessage() != null ? e.getMessage() : "参数不合法");
    }

    @ExceptionHandler(ExternalDependencyBlockedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleExternalDependencyBlocked(ExternalDependencyBlockedException e) {
        log.warn("Sentinel 拒绝外部依赖调用: {}", e.getMessage());
        return Result.error("AI 检测服务当前繁忙或正在恢复，请稍后重试。");
    }

    @ExceptionHandler(InferenceCapacityExceededException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleInferenceCapacityExceeded(InferenceCapacityExceededException e) {
        log.warn("AI 推理容量准入失败: {}", e.getMessage());
        return Result.error("AI 推理资源繁忙，请稍后重试。");
    }

    @ExceptionHandler(TaskQueueFullException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleTaskQueueFull(TaskQueueFullException e) {
        log.warn("推理任务排队容量已满: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(TokenBudgetExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result<Void> handleTokenBudgetExceeded(TokenBudgetExceededException e) {
        log.warn("LLM Token 预算拒绝: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        log.error("服务运行时异常", e);
        return Result.error("服务处理失败，请稍后重试。");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("未预期的错误", e);
        return Result.error("系统异常，请联系管理员。");
    }
}
