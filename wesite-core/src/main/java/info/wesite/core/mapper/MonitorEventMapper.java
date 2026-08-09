package info.wesite.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.MonitorEvent;

@Mapper
public interface MonitorEventMapper extends BaseMapper<MonitorEvent> {

    @Select("SELECT * FROM WEB_MONITOR_EVENT "
        + "WHERE WATCH_ID = #{watchId} AND FINGERPRINT = #{fingerprint} AND DELETED = 0 FOR UPDATE")
    MonitorEvent selectByIdentityForUpdate(
        @Param("watchId") String watchId,
        @Param("fingerprint") String fingerprint);
}
