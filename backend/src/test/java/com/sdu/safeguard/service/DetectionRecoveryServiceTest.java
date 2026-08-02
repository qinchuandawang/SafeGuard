package com.sdu.safeguard.service;

import com.sdu.safeguard.entity.AsyncTask;
import com.sdu.safeguard.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionRecoveryServiceTest {

    @Mock
    private AsyncTaskMapper asyncTaskMapper;
    @Mock
    private DetectionTaskManager taskManager;
    @Mock
    private DetectionService detectionService;
    @Mock
    private LLMService llmService;
    @Mock
    private ObjectStorageService objectStorageService;

    private DetectionRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new DetectionRecoveryService(asyncTaskMapper, taskManager,
                detectionService, llmService, objectStorageService);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "queuedTimeoutSeconds", 60L);
        ReflectionTestUtils.setField(service, "processingTimeoutSeconds", 900L);
        ReflectionTestUtils.setField(service, "batchSize", 20);
    }

    @Test
    void 多实例通过数据库条件更新竞争恢复执行权() {
        AsyncTask task = AsyncTask.builder()
                .taskId("task-1")
                .type("video")
                .status("queued")
                .filePath("D:/tmp/video.mp4")
                .build();
        when(asyncTaskMapper.findRecoverable(any(LocalDateTime.class), any(LocalDateTime.class), eq(20)))
                .thenReturn(List.of(task));
        when(asyncTaskMapper.claimForRecovery(eq("task-1"), eq("queued"), any(LocalDateTime.class)))
                .thenReturn(1);

        service.recoverStaleTasks();

        verify(taskManager).prepareRecoveredTask(task);
        verify(taskManager).runAsync(eq("task-1"), any(DetectionTaskManager.RunnableWithTaskId.class));
    }
}
