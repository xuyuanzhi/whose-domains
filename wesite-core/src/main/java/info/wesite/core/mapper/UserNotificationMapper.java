package info.wesite.core.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.UserNotification;
import info.wesite.core.mapper.model.NotificationDigestRecipientRow;

@Mapper
public interface UserNotificationMapper extends BaseMapper<UserNotification> {

    @Select("SELECT * FROM WEB_USER_NOTIFICATION "
        + "WHERE USER_ID = #{userId} AND EVENT_ID = #{eventId} FOR UPDATE")
    UserNotification selectByIdentityForUpdate(
        @Param("userId") String userId,
        @Param("eventId") String eventId);

    @Select("SELECT * FROM WEB_USER_NOTIFICATION WHERE ID = #{id} AND EMAIL_MODE = 'IMMEDIATE_EMAIL' "
        + "AND EMAIL_STATE = 'QUEUED' AND DELIVERY_BATCH_ID IS NULL AND DELETED = 0 "
        + "AND RECIPIENT_EMAIL IS NOT NULL FOR UPDATE")
    UserNotification selectImmediateForUpdate(@Param("id") String id);

    @Select("SELECT ID FROM WEB_USER_NOTIFICATION WHERE EMAIL_MODE = 'IMMEDIATE_EMAIL' "
        + "AND EMAIL_STATE = 'QUEUED' AND DELIVERY_BATCH_ID IS NULL AND DELETED = 0 "
        + "AND RECIPIENT_EMAIL IS NOT NULL "
        + "AND ID > #{afterId} ORDER BY ID LIMIT #{limit}")
    List<String> selectImmediateCandidateIds(
        @Param("afterId") String afterId,
        @Param("limit") int limit);

    @Select("SELECT USER_ID AS userId, RECIPIENT_EMAIL AS recipientEmail FROM WEB_USER_NOTIFICATION "
        + "WHERE EMAIL_MODE = #{emailMode} "
        + "AND EMAIL_STATE = 'QUEUED' AND DELIVERY_BATCH_ID IS NULL AND DELETED = 0 "
        + "AND RECIPIENT_EMAIL IS NOT NULL AND CREATE_TIME <= #{cutoff} "
        + "AND (USER_ID > #{afterUserId} OR (USER_ID = #{afterUserId} "
        + "AND RECIPIENT_EMAIL > #{afterRecipientEmail})) "
        + "GROUP BY USER_ID, RECIPIENT_EMAIL ORDER BY USER_ID, RECIPIENT_EMAIL LIMIT #{limit}")
    List<NotificationDigestRecipientRow> selectDigestCandidates(
        @Param("emailMode") String emailMode,
        @Param("cutoff") Date cutoff,
        @Param("afterUserId") String afterUserId,
        @Param("afterRecipientEmail") String afterRecipientEmail,
        @Param("limit") int limit);

    @Update("UPDATE WEB_USER_NOTIFICATION SET DELIVERY_BATCH_ID = #{batchId}, UPDATE_TIME = #{updatedAt} "
        + "WHERE ID = #{notificationId} AND EMAIL_STATE = 'QUEUED' "
        + "AND DELIVERY_BATCH_ID IS NULL AND DELETED = 0")
    int assignImmediateToBatch(
        @Param("notificationId") String notificationId,
        @Param("batchId") String batchId,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET DELIVERY_BATCH_ID = #{batchId}, UPDATE_TIME = #{updatedAt} "
        + "WHERE USER_ID = #{userId} AND EMAIL_MODE = #{emailMode} "
        + "AND RECIPIENT_EMAIL = #{recipientEmail} AND EMAIL_STATE = 'QUEUED' "
        + "AND DELIVERY_BATCH_ID IS NULL AND CREATE_TIME <= #{cutoff} AND DELETED = 0")
    int assignDigestToBatch(
        @Param("userId") String userId,
        @Param("emailMode") String emailMode,
        @Param("recipientEmail") String recipientEmail,
        @Param("batchId") String batchId,
        @Param("cutoff") Date cutoff,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = 'CLAIMED', EMAIL_ATTEMPT_COUNT = #{attempt}, "
        + "EMAIL_CLAIM_TOKEN = #{claimToken}, EMAIL_CLAIM_UNTIL = #{claimUntil}, UPDATE_TIME = #{updatedAt} "
        + "WHERE DELIVERY_BATCH_ID = #{batchId} "
        + "AND EMAIL_STATE IN ('QUEUED','FAILED','CLAIMED')")
    int claimBatchForAttempt(
        @Param("batchId") String batchId,
        @Param("attempt") int attempt,
        @Param("claimToken") String claimToken,
        @Param("claimUntil") Date claimUntil,
        @Param("updatedAt") Date updatedAt);

    @Select("SELECT * FROM WEB_USER_NOTIFICATION WHERE DELIVERY_BATCH_ID = #{batchId} "
        + "AND EMAIL_STATE = 'CLAIMED' ORDER BY CREATE_TIME, ID")
    List<UserNotification> selectBatchMembers(@Param("batchId") String batchId);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = #{state}, EMAILED_AT = #{emailedAt}, "
        + "EMAIL_CLAIM_TOKEN = NULL, EMAIL_CLAIM_UNTIL = NULL, UPDATE_TIME = #{updatedAt} "
        + "WHERE DELIVERY_BATCH_ID = #{batchId} AND EMAIL_STATE = 'CLAIMED' "
        + "AND EMAIL_CLAIM_TOKEN = #{claimToken}")
    int completeBatchNotifications(
        @Param("batchId") String batchId,
        @Param("claimToken") String claimToken,
        @Param("state") String state,
        @Param("emailedAt") Date emailedAt,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = 'FAILED', EMAIL_CLAIM_TOKEN = NULL, "
        + "EMAIL_CLAIM_UNTIL = NULL, UPDATE_TIME = #{updatedAt} "
        + "WHERE DELIVERY_BATCH_ID = #{batchId} AND EMAIL_STATE = 'CLAIMED'")
    int finalizeExpiredBatch(@Param("batchId") String batchId, @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = 'IN_APP_ONLY', EMAIL_MODE = 'IN_APP_ONLY', "
        + "EMAIL_CLAIM_TOKEN = NULL, EMAIL_CLAIM_UNTIL = NULL, UPDATE_TIME = #{updatedAt} "
        + "WHERE DELIVERY_BATCH_ID = #{batchId} AND EMAIL_STATE = 'CLAIMED'")
    int cancelClaimedBatch(@Param("batchId") String batchId, @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = 'IN_APP_ONLY', EMAIL_MODE = 'IN_APP_ONLY', "
        + "EMAIL_CLAIM_TOKEN = NULL, EMAIL_CLAIM_UNTIL = NULL, UPDATE_TIME = #{updatedAt} "
        + "WHERE USER_ID = #{userId} AND EMAIL_STATE IN ('QUEUED','FAILED')")
    int cancelUnclaimedForUser(@Param("userId") String userId, @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION N JOIN WEB_MONITOR_EVENT E ON E.ID = N.EVENT_ID "
        + "SET N.EMAIL_STATE = 'IN_APP_ONLY', N.EMAIL_MODE = 'IN_APP_ONLY', "
        + "N.EMAIL_CLAIM_TOKEN = NULL, N.EMAIL_CLAIM_UNTIL = NULL, N.UPDATE_TIME = #{updatedAt} "
        + "WHERE N.USER_ID = #{userId} AND E.WATCH_ID = #{watchId} "
        + "AND N.EMAIL_STATE IN ('QUEUED','FAILED')")
    int cancelUnclaimedForWatch(
        @Param("userId") String userId,
        @Param("watchId") String watchId,
        @Param("updatedAt") Date updatedAt);

}
