package info.wesite.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import info.wesite.core.entity.NotificationPreference;

public interface NotificationPreferenceMapper extends BaseMapper<NotificationPreference> {

    @Select("SELECT * FROM WEB_NOTIFICATION_PREFERENCE "
        + "WHERE USER_ID = #{userId} AND DELETED = 0 FOR UPDATE")
    NotificationPreference selectByUserIdForUpdate(@Param("userId") String userId);
}
