package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.LlmUsageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LlmUsageRecordMapper extends BaseMapper<LlmUsageRecord> {

    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM llm_usage_record WHERE created_at >= CURRENT_DATE")
    long sumTodayTokens();
}
