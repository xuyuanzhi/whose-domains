package info.wesite.core.mapper;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.NotificationDeliveryBatch;

@Mapper
public interface NotificationDeliveryBatchMapper extends BaseMapper<NotificationDeliveryBatch> {

    @Select("SELECT * FROM WEB_NOTIFICATION_DELIVERY_BATCH "
        + "WHERE USER_ID = #{userId} AND STATE IN ('FAILED','CLAIMED') AND DELETED = 0 "
        + "ORDER BY ID FOR UPDATE")
    List<NotificationDeliveryBatch> selectForUserForUpdate(@Param("userId") String userId);

    @Select("SELECT B.* FROM WEB_NOTIFICATION_DELIVERY_BATCH B "
        + "WHERE B.USER_ID = #{userId} AND B.STATE IN ('FAILED','CLAIMED') AND B.DELETED = 0 "
        + "AND EXISTS (SELECT 1 FROM WEB_USER_NOTIFICATION N "
        + "JOIN WEB_MONITOR_EVENT E ON E.ID = N.EVENT_ID "
        + "WHERE N.DELIVERY_BATCH_ID = B.ID AND E.WATCH_ID = #{watchId}) "
        + "ORDER BY B.ID FOR UPDATE")
    List<NotificationDeliveryBatch> selectForWatchForUpdate(
        @Param("userId") String userId,
        @Param("watchId") String watchId);

    @Select({"<script>",
        "SELECT B.* FROM WEB_NOTIFICATION_DELIVERY_BATCH B ",
        "WHERE B.USER_ID = #{userId} AND B.STATE IN ('FAILED','CLAIMED') AND B.DELETED = 0 ",
        "AND EXISTS (SELECT 1 FROM WEB_USER_NOTIFICATION N ",
        "JOIN WEB_MONITOR_EVENT E ON E.ID = N.EVENT_ID ",
        "WHERE N.DELIVERY_BATCH_ID = B.ID AND E.EVENT_TYPE IN ",
        "<foreach collection='eventTypes' item='eventType' open='(' separator=',' close=')'>",
        "#{eventType}",
        "</foreach>",
        ") ORDER BY B.ID FOR UPDATE",
        "</script>"})
    List<NotificationDeliveryBatch> selectForEventTypesForUpdate(
        @Param("userId") String userId,
        @Param("eventTypes") Set<String> eventTypes);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET STATE = 'CANCELLED', "
        + "NEXT_ATTEMPT_AT = NULL, COMPLETED_AT = #{completedAt}, UPDATE_TIME = #{completedAt} "
        + "WHERE ID = #{id} AND STATE = 'FAILED' AND DELETED = 0")
    int cancelFailedById(@Param("id") String id, @Param("completedAt") Date completedAt);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET CANCELLATION_REQUESTED = 1, "
        + "UPDATE_TIME = #{updatedAt} WHERE ID = #{id} AND STATE = 'CLAIMED' AND DELETED = 0")
    int requestCancellationById(@Param("id") String id, @Param("updatedAt") Date updatedAt);

    @Select("SELECT * FROM WEB_NOTIFICATION_DELIVERY_BATCH "
        + "WHERE EMAIL_MODE = #{emailMode} AND DELETED = 0 AND ATTEMPT_COUNT < #{maxAttempts} AND ("
        + "(STATE = 'FAILED' AND NEXT_ATTEMPT_AT <= #{now}) OR "
        + "(STATE = 'CLAIMED' AND CLAIM_UNTIL < #{now})) "
        + "AND CANCELLATION_REQUESTED = 0 "
        + "ORDER BY COALESCE(NEXT_ATTEMPT_AT, CLAIM_UNTIL), ID LIMIT 1 FOR UPDATE SKIP LOCKED")
    NotificationDeliveryBatch selectRetryableForUpdate(
        @Param("emailMode") String emailMode,
        @Param("now") Date now,
        @Param("maxAttempts") int maxAttempts);

    @Select("SELECT * FROM WEB_NOTIFICATION_DELIVERY_BATCH "
        + "WHERE EMAIL_MODE = #{emailMode} AND STATE = 'CLAIMED' AND DELETED = 0 "
        + "AND CANCELLATION_REQUESTED = 0 "
        + "AND ATTEMPT_COUNT >= #{maxAttempts} AND CLAIM_UNTIL < #{now} "
        + "ORDER BY ID LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<NotificationDeliveryBatch> selectExpiredExhaustedForUpdate(
        @Param("emailMode") String emailMode,
        @Param("now") Date now,
        @Param("maxAttempts") int maxAttempts,
        @Param("limit") int limit);

    @Select("SELECT * FROM WEB_NOTIFICATION_DELIVERY_BATCH "
        + "WHERE EMAIL_MODE = #{emailMode} AND STATE = 'CLAIMED' AND DELETED = 0 "
        + "AND CANCELLATION_REQUESTED = 1 AND CLAIM_UNTIL < #{now} "
        + "ORDER BY ID LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<NotificationDeliveryBatch> selectExpiredCancellationRequestedForUpdate(
        @Param("emailMode") String emailMode,
        @Param("now") Date now,
        @Param("limit") int limit);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET STATE = 'CLAIMED', "
        + "ATTEMPT_COUNT = #{attempt}, CLAIM_TOKEN = #{claimToken}, CLAIM_UNTIL = #{claimUntil}, "
        + "NEXT_ATTEMPT_AT = NULL, UPDATE_TIME = #{updatedAt} WHERE ID = #{id} AND DELETED = 0 "
        + "AND CANCELLATION_REQUESTED = 0")
    int claimRetry(
        @Param("id") String id,
        @Param("attempt") int attempt,
        @Param("claimToken") String claimToken,
        @Param("claimUntil") Date claimUntil,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET STATE = #{state}, CLAIM_TOKEN = NULL, "
        + "CLAIM_UNTIL = NULL, NEXT_ATTEMPT_AT = #{nextAttemptAt}, COMPLETED_AT = #{completedAt}, "
        + "UPDATE_TIME = #{updatedAt} WHERE ID = #{id} AND STATE = 'CLAIMED' "
        + "AND CLAIM_TOKEN = #{claimToken} AND DELETED = 0")
    int completeClaim(
        @Param("id") String id,
        @Param("claimToken") String claimToken,
        @Param("state") String state,
        @Param("completedAt") Date completedAt,
        @Param("nextAttemptAt") Date nextAttemptAt,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET STATE = 'FAILED', CLAIM_TOKEN = NULL, "
        + "CLAIM_UNTIL = NULL, NEXT_ATTEMPT_AT = NULL, COMPLETED_AT = #{completedAt}, "
        + "UPDATE_TIME = #{completedAt} WHERE ID = #{id} AND STATE = 'CLAIMED' AND DELETED = 0")
    int finalizeExpired(@Param("id") String id, @Param("completedAt") Date completedAt);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET STATE = 'CANCELLED', CLAIM_TOKEN = NULL, "
        + "CLAIM_UNTIL = NULL, NEXT_ATTEMPT_AT = NULL, COMPLETED_AT = #{completedAt}, "
        + "UPDATE_TIME = #{completedAt} WHERE ID = #{id} AND STATE = 'CLAIMED' "
        + "AND CANCELLATION_REQUESTED = 1 AND DELETED = 0")
    int finalizeCancelledClaim(@Param("id") String id, @Param("completedAt") Date completedAt);

    @Select("SELECT * FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE ID = #{id} FOR UPDATE")
    NotificationDeliveryBatch selectByIdForUpdate(@Param("id") String id);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET STATE = 'CANCELLED', NEXT_ATTEMPT_AT = NULL, "
        + "COMPLETED_AT = #{completedAt}, UPDATE_TIME = #{completedAt} "
        + "WHERE USER_ID = #{userId} AND STATE = 'FAILED' AND DELETED = 0")
    int cancelFailedForUser(@Param("userId") String userId, @Param("completedAt") Date completedAt);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET CANCELLATION_REQUESTED = 1, "
        + "UPDATE_TIME = #{updatedAt} WHERE USER_ID = #{userId} AND STATE = 'CLAIMED' AND DELETED = 0")
    int requestCancellationForClaimedUser(
        @Param("userId") String userId,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH B SET B.STATE = 'CANCELLED', "
        + "B.NEXT_ATTEMPT_AT = NULL, B.COMPLETED_AT = #{completedAt}, B.UPDATE_TIME = #{completedAt} "
        + "WHERE B.USER_ID = #{userId} AND B.STATE = 'FAILED' AND B.DELETED = 0 AND EXISTS ("
        + "SELECT 1 FROM WEB_USER_NOTIFICATION N JOIN WEB_MONITOR_EVENT E ON E.ID = N.EVENT_ID "
        + "WHERE N.DELIVERY_BATCH_ID = B.ID AND E.WATCH_ID = #{watchId})")
    int cancelFailedForWatch(
        @Param("userId") String userId,
        @Param("watchId") String watchId,
        @Param("completedAt") Date completedAt);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH B SET B.CANCELLATION_REQUESTED = 1, "
        + "B.UPDATE_TIME = #{updatedAt} WHERE B.USER_ID = #{userId} "
        + "AND B.STATE = 'CLAIMED' AND B.DELETED = 0 AND EXISTS ("
        + "SELECT 1 FROM WEB_USER_NOTIFICATION N JOIN WEB_MONITOR_EVENT E ON E.ID = N.EVENT_ID "
        + "WHERE N.DELIVERY_BATCH_ID = B.ID AND E.WATCH_ID = #{watchId})")
    int requestCancellationForClaimedWatch(
        @Param("userId") String userId,
        @Param("watchId") String watchId,
        @Param("updatedAt") Date updatedAt);

    @Update({"<script>",
        "UPDATE WEB_NOTIFICATION_DELIVERY_BATCH B SET B.STATE = 'CANCELLED', ",
        "B.NEXT_ATTEMPT_AT = NULL, B.COMPLETED_AT = #{completedAt}, B.UPDATE_TIME = #{completedAt} ",
        "WHERE B.USER_ID = #{userId} AND B.STATE = 'FAILED' AND B.DELETED = 0 AND EXISTS (",
        "SELECT 1 FROM WEB_USER_NOTIFICATION N JOIN WEB_MONITOR_EVENT E ON E.ID = N.EVENT_ID ",
        "WHERE N.DELIVERY_BATCH_ID = B.ID AND E.EVENT_TYPE IN ",
        "<foreach collection='eventTypes' item='eventType' open='(' separator=',' close=')'>",
        "#{eventType}",
        "</foreach>",
        ")",
        "</script>"})
    int cancelFailedForEventTypes(
        @Param("userId") String userId,
        @Param("eventTypes") Set<String> eventTypes,
        @Param("completedAt") Date completedAt);

    @Update({"<script>",
        "UPDATE WEB_NOTIFICATION_DELIVERY_BATCH B SET B.CANCELLATION_REQUESTED = 1, ",
        "B.UPDATE_TIME = #{updatedAt} WHERE B.USER_ID = #{userId} ",
        "AND B.STATE = 'CLAIMED' AND B.DELETED = 0 AND EXISTS (",
        "SELECT 1 FROM WEB_USER_NOTIFICATION N JOIN WEB_MONITOR_EVENT E ON E.ID = N.EVENT_ID ",
        "WHERE N.DELIVERY_BATCH_ID = B.ID AND E.EVENT_TYPE IN ",
        "<foreach collection='eventTypes' item='eventType' open='(' separator=',' close=')'>",
        "#{eventType}",
        "</foreach>",
        ")",
        "</script>"})
    int requestCancellationForClaimedEventTypes(
        @Param("userId") String userId,
        @Param("eventTypes") Set<String> eventTypes,
        @Param("updatedAt") Date updatedAt);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET STATE = 'CANCELLED', CLAIM_TOKEN = NULL, "
        + "CLAIM_UNTIL = NULL, NEXT_ATTEMPT_AT = NULL, COMPLETED_AT = #{completedAt}, "
        + "UPDATE_TIME = #{completedAt} WHERE ID = #{id} AND STATE IN ('FAILED','CLAIMED')")
    int cancelWithoutMembers(@Param("id") String id, @Param("completedAt") Date completedAt);
}
