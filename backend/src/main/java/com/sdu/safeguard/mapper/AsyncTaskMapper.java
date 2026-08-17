package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {

    @Select("SELECT * FROM async_task WHERE task_id = #{taskId} AND deleted = 0")
    AsyncTask findByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM async_task WHERE idempotency_key = #{idempotencyKey} AND deleted = 0 LIMIT 1")
    AsyncTask findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT COUNT(*) FROM async_task WHERE type = #{type} AND status IN ('queued', 'processing') AND deleted = 0")
    int countActiveByType(@Param("type") String type);

    @Select("SELECT * FROM async_task WHERE status IN ('queued', 'processing') AND created_at < #{before} AND deleted = 0")
    List<AsyncTask> findStuckTasks(@Param("before") LocalDateTime before);

    @Update("UPDATE async_task SET status = 'failed', error_message = '服务重启，任务中断', completed_at = NOW() WHERE task_id = #{taskId}")
    void markInterrupted(@Param("taskId") String taskId);

    @Update("UPDATE async_task SET status = 'processing', version = version + 1 " +
            "WHERE task_id = #{taskId} AND status = 'queued' AND version = #{version} AND deleted = 0")
    int markProcessing(@Param("taskId") String taskId, @Param("version") int version);

    @Update("UPDATE async_task SET status = 'queued', error_message = NULL, version = version + 1, updated_at = NOW() " +
            "WHERE task_id = #{taskId} AND status = 'processing' AND deleted = 0")
    int requeueProcessing(@Param("taskId") String taskId);

    @Update("UPDATE async_task SET progress = #{progress}, version = version + 1 " +
            "WHERE task_id = #{taskId} AND status = 'processing' AND progress <= #{progress} AND deleted = 0")
    int updateProgressIfProcessing(@Param("taskId") String taskId, @Param("progress") int progress);

    @Update("UPDATE async_task SET status = #{status}, progress = #{progress}, result_json = #{resultJson}, " +
            "error_message = #{error}, completed_at = NOW(), version = version + 1 " +
            "WHERE task_id = #{taskId} AND status = 'processing' AND deleted = 0")
    int finishIfProcessing(@Param("taskId") String taskId, @Param("status") String status,
                           @Param("progress") int progress, @Param("resultJson") String resultJson,
                           @Param("error") String error);

    @Update("UPDATE async_task SET status = 'waiting_review', progress = 90, result_json = #{resultJson}, " +
            "version = version + 1, updated_at = NOW() " +
            "WHERE task_id = #{taskId} AND status = 'processing' AND deleted = 0")
    int markWaitingReview(@Param("taskId") String taskId, @Param("resultJson") String resultJson);

    @Update("UPDATE async_task SET status = 'completed', progress = 100, result_json = #{resultJson}, " +
            "error_message = NULL, completed_at = NOW(), version = version + 1 " +
            "WHERE task_id = #{taskId} AND status = 'waiting_review' AND deleted = 0")
    int finishIfWaitingReview(@Param("taskId") String taskId, @Param("resultJson") String resultJson);

    @Update("UPDATE async_task SET object_key = #{objectKey}, file_path = NULL, " +
            "version = version + 1 WHERE task_id = #{taskId} AND deleted = 0")
    int bindObjectKey(@Param("taskId") String taskId, @Param("objectKey") String objectKey);

    @Update("UPDATE async_task SET object_key = #{objectKey}, file_path = #{filePath}, " +
            "version = version + 1 WHERE task_id = #{taskId} AND status = 'queued' AND deleted = 0")
    int bindInputLocation(@Param("taskId") String taskId, @Param("objectKey") String objectKey,
                          @Param("filePath") String filePath);

    @Select("SELECT * FROM async_task WHERE type IN ('video', 'multimodal') AND " +
            "((status = 'queued' AND updated_at < #{queuedBefore}) OR " +
            "(status = 'processing' AND updated_at < #{processingBefore})) " +
            "AND (object_key IS NOT NULL OR file_path IS NOT NULL) AND deleted = 0 ORDER BY id LIMIT #{limit}")
    java.util.List<AsyncTask> findRecoverable(@Param("queuedBefore") LocalDateTime queuedBefore,
                                               @Param("processingBefore") LocalDateTime processingBefore,
                                               @Param("limit") int limit);

    @Update("UPDATE async_task SET status = 'queued', progress = 0, error_message = NULL, " +
            "version = version + 1, updated_at = NOW() WHERE task_id = #{taskId} " +
            "AND status = #{expectedStatus} AND updated_at < #{staleBefore} AND deleted = 0")
    int claimForRecovery(@Param("taskId") String taskId, @Param("expectedStatus") String expectedStatus,
                         @Param("staleBefore") LocalDateTime staleBefore);
}
