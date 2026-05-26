package com.sdu.safeguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectionTask {
    private String taskId;
    private String type;
    /** 状态字段，多线程下 volatile 保证可见性 */
    private volatile String status;
    /** 进度字段，多线程下 volatile 保证可见性 */
    private volatile int progress;
    /** 结果字段，多线程下 volatile 保证可见性 */
    private volatile Object result;
    /** 错误信息，多线程下 volatile 保证可见性 */
    private volatile String error;
    private SseEmitter sseEmitter;
}
