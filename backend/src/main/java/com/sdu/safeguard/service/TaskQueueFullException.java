package com.sdu.safeguard.service;

/**
 * 推理排队容量已满时使用，调用方应按可重试的 503 处理。
 */
public class TaskQueueFullException extends RuntimeException {

    public TaskQueueFullException(String message) {
        super(message);
    }
}
