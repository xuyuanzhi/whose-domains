package info.wesite.core.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.NotificationDeliveryBatch;

@Mapper
public interface NotificationDeliveryBatchMapper extends BaseMapper<NotificationDeliveryBatch> {

    @Select("SELECT * FROM WEB_NOTIFICATION_DELIVERY_BATCH "
        + "WHERE EMAIL_MODE = #{emailMode} AND DELETED = 0 AND ATTEMPT_COUNT < #{maxAttempts} AND ("
        + "(STATE = 'FAILED' AND NEXT_ATTEMPT_AT <= #{now}) OR "
        + "(STATE = 'CLAIMED' AND CLAIM_UNTIL < #{now})) "
        + "ORDER BY COALESCE(NEXT_ATTEMPT_AT, CLAIM_UNTIL), ID LIMIT 1 FOR UPDATE SKIP LOCKED")
    NotificationDeliveryBatch selectRetryableForUpdate(
        @Param("emailMode") String emailMode,
        @Param("now") Date now,
        @Param("maxAttempts") int maxAttempts);

    @Select("SELECT * FROM WEB_NOTIFICATION_DELIVERY_BATCH "
        + "WHERE EMAIL_MODE = #{emailMode} AND STATE = 'CLAIMED' AND DELETED = 0 "
        + "AND ATTEMPT_COUNT >= #{maxAttempts} AND CLAIM_UNTIL < #{now} "
        + "ORDER BY CLAIM_UNTIL, ID LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<NotificationDeliveryBatch> selectExpiredExhaustedForUpdate(
        @Param("emailMode") String emailMode,
        @Param("now") Date now,
        @Param("maxAttempts") int maxAttempts,
        @Param("limit") int limit);

    @Update("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET STATE = 'CLAIMED', "
        + "ATTEMPT_COUNT = #{attempt}, CLAIM_TOKEN = #{claimToken}, CLAIM_UNTIL = #{claimUntil}, "
        + "NEXT_ATTEMPT_AT = NULL, UPDATE_TIME = #{updatedAt} WHERE ID = #{id} AND DELETED = 0")
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
}
