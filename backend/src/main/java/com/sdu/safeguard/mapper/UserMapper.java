package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM user WHERE openid = #{openid} AND deleted = 0")
    User findByOpenid(@Param("openid") String openid);

    @Select("SELECT * FROM user WHERE role = 'admin' AND deleted = 0 LIMIT 1")
    User findAnyAdmin();

    @Select("SELECT * FROM user WHERE openid = #{openid} AND deleted = 0 LIMIT 1")
    User findByOpenidAny(@Param("openid") String openid);

    /**
     * 按日期统计每日新增用户数（最近N天）
     */
    @Select("SELECT DATE(created_at) AS date, COUNT(*) AS count FROM user WHERE created_at >= #{since} AND deleted = 0 GROUP BY DATE(created_at) ORDER BY date ASC")
    java.util.List<java.util.Map<String, Object>> countByDateGroup(@Param("since") java.time.LocalDateTime since);

    /**
     * 最近 N 条用户（按时间倒序）。全表查询的兜底版本，避免生产环境 OOM。
     */
    @Select("SELECT * FROM user WHERE deleted = 0 ORDER BY created_at DESC LIMIT #{limit}")
    java.util.List<User> findRecentUsers(@Param("limit") int limit);
}
