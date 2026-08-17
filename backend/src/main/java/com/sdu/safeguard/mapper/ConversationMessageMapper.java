package com.sdu.safeguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sdu.safeguard.entity.ConversationMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    /**
     * 以主键游标向前翻页，避免长会话使用 OFFSET 导致扫描量随页数增长。
     * 查询结果为倒序，服务层会恢复为时间正序。
     */
    @Select("<script>SELECT * FROM conversation_message WHERE conversation_id=#{conversationId} "
            + "<if test='beforeId != null'>AND id &lt; #{beforeId} </if>"
            + "ORDER BY id DESC LIMIT #{limit}</script>")
    List<ConversationMessage> findRecentBefore(@Param("conversationId") String conversationId,
                                               @Param("beforeId") Long beforeId,
                                               @Param("limit") int limit);

    @Delete("DELETE FROM conversation_message WHERE conversation_id=#{conversationId}")
    int deleteByConversationId(@Param("conversationId") String conversationId);
}
