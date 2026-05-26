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

    @Select("SELECT * FROM async_task WHERE status = 'processing' AND created_at < #{before} AND deleted = 0")
    List<AsyncTask> findStuckTasks(@Param("before") LocalDateTime before);

    @Select("SELECT * FROM async_task WHERE user_id = #{userId} AND deleted = 0 ORDER BY created_at DESC")
    List<AsyncTask> findByUserId(@Param("userId") Long userId);

    @Update("UPDATE async_task SET status = 'failed', error_message = '服务重启，任务中断', completed_at = NOW() WHERE task_id = #{taskId}")
    void markInterrupted(@Param("taskId") String taskId);
}
