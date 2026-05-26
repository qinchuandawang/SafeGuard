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
}
