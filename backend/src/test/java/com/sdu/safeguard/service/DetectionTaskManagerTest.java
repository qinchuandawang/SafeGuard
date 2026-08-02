package com.sdu.safeguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdu.safeguard.dto.DetectionTask;
import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DetectionTaskManagerTest {

    @Mock
    private AsyncTaskMapper asyncTaskMapper;
    @Mock
    private TaskEventService taskEventService;
    @Mock
    private IdempotencyService idempotencyService;

    private DetectionTaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new DetectionTaskManager(asyncTaskMapper, new ObjectMapper(), taskEventService, idempotencyService);
        lenient().when(taskEventService.execute(anyString(), anyString(), any(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        lenient().when(taskEventService.execute(anyString(), anyString(), any(), any(Supplier.class), any(Predicate.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
    }

    @Test
    void 相同幂等键只创建一次并返回同一任务() {
        String key = "video:hash:model-a";
        when(idempotencyService.findTaskId(key))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.ofNullable(createdTaskId()));
        when(idempotencyService.reserve(anyString(), anyString())).thenReturn(true);
        when(asyncTaskMapper.insert(any(AsyncTask.class))).thenAnswer(invocation -> {
            AsyncTask task = invocation.getArgument(0);
            lastTaskId = task.getTaskId();
            return 1;
        });

        DetectionTaskManager.TaskCreation first = manager.createTaskIdempotently(
                "video", null, key, "hash", "model-a");
        DetectionTaskManager.TaskCreation second = manager.createTaskIdempotently(
                "video", null, key, "hash", "model-a");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.task().getTaskId()).isEqualTo(first.task().getTaskId());
        verify(asyncTaskMapper, times(1)).insert(any(AsyncTask.class));
    }

    @Test
    void 并发请求等待创建中的任务并复用结果() {
        String key = "video:hotspot:model-a";
        when(idempotencyService.findTaskId(key))
                .thenReturn(Optional.empty(), Optional.of("winner-task"));
        when(idempotencyService.reserve(anyString(), anyString())).thenReturn(false);
        when(asyncTaskMapper.findByTaskId("winner-task")).thenReturn(AsyncTask.builder()
                .taskId("winner-task").type("video").status("queued").progress(0).build());

        DetectionTaskManager.TaskCreation creation = manager.createTaskIdempotently(
                "video", null, key, "hash", "model-a");

        assertThat(creation.created()).isFalse();
        assertThat(creation.task().getTaskId()).isEqualTo("winner-task");
        verify(asyncTaskMapper, times(0)).insert(any(AsyncTask.class));
    }

    @Test
    void 终态更新只能成功一次() {
        when(idempotencyService.reserve(any(), anyString())).thenReturn(true);
        when(asyncTaskMapper.insert(any(AsyncTask.class))).thenReturn(1);
        when(asyncTaskMapper.findByTaskId(anyString())).thenAnswer(invocation -> AsyncTask.builder()
                .taskId(invocation.getArgument(0)).status("queued").version(0).build());
        when(asyncTaskMapper.markProcessing(anyString(), any(Integer.class))).thenReturn(1);
        when(asyncTaskMapper.finishIfProcessing(anyString(), anyString(), any(Integer.class), any(), any()))
                .thenReturn(1);

        DetectionTask task = manager.createTaskIdempotently("video", null, null, null, null).task();
        manager.runAsync(task.getTaskId(), taskId -> manager.complete(taskId, "完成"));
        manager.complete(task.getTaskId(), "重复完成");

        verify(asyncTaskMapper, times(1))
                .finishIfProcessing(anyString(), anyString(), any(Integer.class), any(), any());
        assertThat(manager.getTask(task.getTaskId()).getStatus()).isEqualTo("completed");
    }

    @Test
    void 同一任务的多个SSE订阅者共享一个刷新任务() {
        when(asyncTaskMapper.findByTaskId("task-stream")).thenReturn(AsyncTask.builder()
                .taskId("task-stream").type("video").status("processing").progress(20).build());

        SseEmitter first = manager.subscribe("task-stream");
        SseEmitter second = manager.subscribe("task-stream");

        @SuppressWarnings("unchecked")
        Map<String, ?> refreshTasks = (Map<String, ?>) ReflectionTestUtils.getField(manager, "taskRefreshTasks");
        assertThat(refreshTasks).hasSize(1).containsKey("task-stream");
        ReflectionTestUtils.invokeMethod(manager, "closeTaskEmitters", "task-stream");
        assertThat(refreshTasks).isEmpty();
    }

    private String lastTaskId;

    private String createdTaskId() {
        return lastTaskId;
    }
}
