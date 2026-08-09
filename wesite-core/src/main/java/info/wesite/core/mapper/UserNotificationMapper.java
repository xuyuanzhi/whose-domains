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

    @Select("SELECT * FROM WEB_USER_NOTIFICATION WHERE ID = #{id} AND EMAIL_MODE = 'IMMEDIATE_EMAIL' "
        + "AND EMAIL_STATE = 'QUEUED' AND DELIVERY_BATCH_ID IS NULL AND DELETED = 0 FOR UPDATE")
    UserNotification selectImmediateForUpdate(@Param("id") String id);

    @Select("SELECT ID FROM WEB_USER_NOTIFICATION WHERE EMAIL_MODE = 'IMMEDIATE_EMAIL' "
        + "AND EMAIL_STATE = 'QUEUED' AND DELIVERY_BATCH_ID IS NULL AND DELETED = 0 "
        + "AND ID > #{afterId} ORDER BY ID LIMIT #{limit}")
    List<String> selectImmediateCandidateIds(
        @Param("afterId") String afterId,
        @Param("limit") int limit);

    @Select("SELECT DISTINCT USER_ID FROM WEB_USER_NOTIFICATION WHERE EMAIL_MODE = #{emailMode} "
        + "AND EMAIL_STATE = 'QUEUED' AND DELIVERY_BATCH_ID IS NULL AND DELETED = 0 "
        + "AND CREATE_TIME <= #{cutoff} AND USER_ID > #{afterUserId} "
        + "ORDER BY USER_ID LIMIT #{limit}")
    List<String> selectDigestCandidateUserIds(
        @Param("emailMode") String emailMode,
        @Param("cutoff") Date cutoff,
        @Param("afterUserId") String afterUserId,
        @Param("limit") int limit);

    @Update("UPDATE WEB_USER_NOTIFICATION SET DELIVERY_BATCH_ID = #{batchId}, UPDATE_TIME = #{updatedAt} "
        + "WHERE ID = #{notificationId} AND EMAIL_STATE = 'QUEUED' "
        + "AND DELIVERY_BATCH_ID IS NULL AND DELETED = 0")
    int assignImmediateToBatch(
        @Param("notificationId") String notificationId,
        @Param("batchId") String batchId,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET DELIVERY_BATCH_ID = #{batchId}, UPDATE_TIME = #{updatedAt} "
        + "WHERE USER_ID = #{userId} AND EMAIL_MODE = #{emailMode} AND EMAIL_STATE = 'QUEUED' "
        + "AND DELIVERY_BATCH_ID IS NULL AND CREATE_TIME <= #{cutoff} AND DELETED = 0")
    int assignDigestToBatch(
        @Param("userId") String userId,
        @Param("emailMode") String emailMode,
        @Param("batchId") String batchId,
        @Param("cutoff") Date cutoff,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = 'CLAIMED', EMAIL_ATTEMPT_COUNT = #{attempt}, "
        + "EMAIL_CLAIM_TOKEN = #{claimToken}, EMAIL_CLAIM_UNTIL = #{claimUntil}, UPDATE_TIME = #{updatedAt} "
        + "WHERE DELIVERY_BATCH_ID = #{batchId} AND DELETED = 0")
    int claimBatchForAttempt(
        @Param("batchId") String batchId,
        @Param("attempt") int attempt,
        @Param("claimToken") String claimToken,
        @Param("claimUntil") Date claimUntil,
        @Param("updatedAt") Date updatedAt);

    @Select("SELECT * FROM WEB_USER_NOTIFICATION WHERE DELIVERY_BATCH_ID = #{batchId} "
        + "AND DELETED = 0 ORDER BY CREATE_TIME, ID")
    List<UserNotification> selectBatchMembers(@Param("batchId") String batchId);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = #{state}, EMAILED_AT = #{emailedAt}, "
        + "EMAIL_CLAIM_TOKEN = NULL, EMAIL_CLAIM_UNTIL = NULL, UPDATE_TIME = #{updatedAt} "
        + "WHERE DELIVERY_BATCH_ID = #{batchId} AND EMAIL_STATE = 'CLAIMED' "
        + "AND EMAIL_CLAIM_TOKEN = #{claimToken} AND DELETED = 0")
    int completeBatchNotifications(
        @Param("batchId") String batchId,
        @Param("claimToken") String claimToken,
        @Param("state") String state,
        @Param("emailedAt") Date emailedAt,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_USER_NOTIFICATION SET EMAIL_STATE = 'FAILED', EMAIL_CLAIM_TOKEN = NULL, "
        + "EMAIL_CLAIM_UNTIL = NULL, UPDATE_TIME = #{updatedAt} "
        + "WHERE DELIVERY_BATCH_ID = #{batchId} AND EMAIL_STATE = 'CLAIMED' AND DELETED = 0")
    int finalizeExpiredBatch(@Param("batchId") String batchId, @Param("updatedAt") Date updatedAt);

}
