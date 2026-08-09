package info.wesite.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.UserNotification;

@Mapper
public interface UserNotificationMapper extends BaseMapper<UserNotification> {

    @Select("SELECT * FROM WEB_USER_NOTIFICATION "
        + "WHERE USER_ID = #{userId} AND EVENT_ID = #{eventId} AND DELETED = 0 FOR UPDATE")
    UserNotification selectByIdentityForUpdate(
        @Param("userId") String userId,
        @Param("eventId") String eventId);
}
