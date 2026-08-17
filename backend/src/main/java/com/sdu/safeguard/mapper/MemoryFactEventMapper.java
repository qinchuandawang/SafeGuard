package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.MemoryFactEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MemoryFactEventMapper extends BaseMapper<MemoryFactEvent> {

    @Select("SELECT * FROM memory_fact_event WHERE owner_key=#{ownerKey} AND fact_key=#{factKey} "
            + "ORDER BY version DESC LIMIT 1 FOR UPDATE")
    MemoryFactEvent findLatestForUpdate(@Param("ownerKey") String ownerKey,
                                        @Param("factKey") String factKey);

    @Select("SELECT * FROM memory_fact_event WHERE owner_key=#{ownerKey} AND fact_key=#{factKey} "
            + "AND status='ACTIVE' ORDER BY version DESC LIMIT 1 FOR UPDATE")
    MemoryFactEvent findActiveForUpdate(@Param("ownerKey") String ownerKey,
                                        @Param("factKey") String factKey);

    @Update("UPDATE memory_fact_event SET status=#{status} WHERE owner_key=#{ownerKey} "
            + "AND fact_key=#{factKey} AND status IN ('ACTIVE','CONFLICTED')")
    int updateUnresolvedStatus(@Param("ownerKey") String ownerKey,
                               @Param("factKey") String factKey,
                               @Param("status") String status);

    @Select("SELECT * FROM memory_fact_event WHERE status='ACTIVE' ORDER BY created_at ASC")
    List<MemoryFactEvent> findAllActive();
}
