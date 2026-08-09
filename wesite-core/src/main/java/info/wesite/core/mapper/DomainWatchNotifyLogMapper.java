package info.wesite.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.DomainWatchNotifyLog;

@Mapper
public interface DomainWatchNotifyLogMapper extends BaseMapper<DomainWatchNotifyLog> {

    @Update("UPDATE WEB_DOMAIN_WATCH_NOTIFY_LOG SET SEND_STATUS = #{sendStatus}, SENT_AT = #{sentAt}, "
        + "ERROR_MSG = #{errorMsg}, NOTIFICATION_ID = COALESCE(#{notificationId}, NOTIFICATION_ID), "
        + "EVENT_ID = COALESCE(#{eventId}, EVENT_ID), WATCH_ID = COALESCE(#{watchId}, WATCH_ID), "
        + "TO_EMAIL = COALESCE(#{toEmail}, TO_EMAIL), DOMAIN_NAME = COALESCE(#{domainName}, DOMAIN_NAME), "
        + "DAYS_LEFT = COALESCE(#{daysLeft}, DAYS_LEFT), SUBJECT = COALESCE(#{subject}, SUBJECT), "
        + "UPDATE_TIME = #{sentAt} WHERE BATCH_ID = #{batchId} AND RETRY_COUNT = #{attempt} "
        + "AND SEND_STATUS = 0 AND DELETED = 0")
    int finishPendingAttempt(
        @Param("batchId") String batchId,
        @Param("attempt") int attempt,
        @Param("sendStatus") int sendStatus,
        @Param("sentAt") Date sentAt,
        @Param("errorMsg") String errorMsg,
        @Param("notificationId") String notificationId,
        @Param("eventId") String eventId,
        @Param("watchId") String watchId,
        @Param("toEmail") String toEmail,
        @Param("domainName") String domainName,
        @Param("daysLeft") Integer daysLeft,
        @Param("subject") String subject);
}
