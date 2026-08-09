package info.wesite.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.DomainWatchNotifyLog;

@Mapper
public interface DomainWatchNotifyLogMapper extends BaseMapper<DomainWatchNotifyLog> {

    @Select("SELECT COALESCE(MAX(RETRY_COUNT), 0) FROM WEB_DOMAIN_WATCH_NOTIFY_LOG "
        + "WHERE NOTIFICATION_ID = #{notificationId} AND DELIVERY_MODE = #{deliveryMode} AND DELETED = 0")
    Integer selectMaxRetryCount(
        @Param("notificationId") String notificationId,
        @Param("deliveryMode") String deliveryMode);
}
