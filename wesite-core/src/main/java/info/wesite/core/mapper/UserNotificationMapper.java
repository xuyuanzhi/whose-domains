package info.wesite.core.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.UserNotification;

@Mapper
public interface UserNotificationMapper extends BaseMapper<UserNotification> {

    @Select("SELECT * FROM WEB_USER_NOTIFICATION "
        + "WHERE USER_ID = #{userId} AND EVENT_ID = #{eventId} AND DELETED = 0 FOR UPDATE")
    UserNotification selectByIdentityForUpdate(
        @Param("userId") String userId,
        @Param("eventId") String eventId);

    @Select("SELECT n.* FROM WEB_USER_NOTIFICATION n "
        + "WHERE n.STATUS = 1 AND n.DELETED = 0 AND ("
        + "n.EMAIL_STATE = #{deliveryMode} OR ("
        + "n.EMAIL_STATE = 'FAILED' AND "
        + "EXISTS (SELECT 1 FROM WEB_DOMAIN_WATCH_NOTIFY_LOG route_log "
        + "WHERE route_log.NOTIFICATION_ID = n.ID "
        + "AND route_log.DELIVERY_MODE = #{deliveryMode} AND route_log.DELETED = 0) AND "
        + "(SELECT COALESCE(MAX(attempt_log.RETRY_COUNT), 0) "
        + "FROM WEB_DOMAIN_WATCH_NOTIFY_LOG attempt_log "
        + "WHERE attempt_log.NOTIFICATION_ID = n.ID "
        + "AND attempt_log.DELIVERY_MODE = #{deliveryMode} AND attempt_log.DELETED = 0) < #{maxAttempts})) "
        + "ORDER BY n.CREATE_TIME ASC, n.ID ASC LIMIT #{limit}")
    List<UserNotification> selectForDelivery(
        @Param("deliveryMode") String deliveryMode,
        @Param("maxAttempts") int maxAttempts,
        @Param("limit") int limit);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = 'SENDING', UPDATE_TIME = #{claimedAt} "
        + "WHERE ID = #{id} AND EMAIL_STATE = #{expectedState} AND DELETED = 0")
    int claimForDelivery(
        @Param("id") String id,
        @Param("expectedState") String expectedState,
        @Param("claimedAt") Date claimedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = #{newState}, "
        + "EMAILED_AT = #{emailedAt}, UPDATE_TIME = #{updatedAt} "
        + "WHERE ID = #{id} AND EMAIL_STATE = 'SENDING' AND DELETED = 0")
    int finishDelivery(
        @Param("id") String id,
        @Param("newState") String newState,
        @Param("emailedAt") Date emailedAt,
        @Param("updatedAt") Date updatedAt);
}
