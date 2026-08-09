package info.wesite.core.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import info.wesite.core.mapper.model.DomainWatchLatestCheckRow;
import info.wesite.core.mapper.model.DomainWatchLatestEventRow;
import info.wesite.core.mapper.model.DomainWatchUnreadCountRow;

@Mapper
public interface DomainWatchSummaryMapper {

    @Select({
        "<script>",
        "SELECT WATCH_ID AS watchId, RISK AS latestRisk, EVENT_TYPE AS latestEventType, NEW_VALUE AS latestEventValue",
        "FROM (",
        "  SELECT E.WATCH_ID, E.RISK, E.EVENT_TYPE, E.NEW_VALUE,",
        "    ROW_NUMBER() OVER (PARTITION BY E.WATCH_ID ORDER BY E.OCCURRED_AT DESC,",
        "      CASE E.RISK WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END DESC,",
        "      E.ID DESC) AS ROW_NUMBER_VALUE",
        "  FROM WEB_MONITOR_EVENT E",
        "  WHERE E.DELETED = 0 AND E.WATCH_ID IN",
        "  <foreach collection='watchIds' item='watchId' open='(' separator=',' close=')'>#{watchId}</foreach>",
        ") latest_events",
        "WHERE ROW_NUMBER_VALUE = 1",
        "</script>"
    })
    List<DomainWatchLatestEventRow> selectLatestEvents(@Param("watchIds") List<String> watchIds);

    @Select({
        "<script>",
        "SELECT WATCH_ID AS watchId, CHECKED_AT AS lastSuccessfulCheck",
        "FROM (",
        "  SELECT S.WATCH_ID, S.CHECKED_AT,",
        "    ROW_NUMBER() OVER (PARTITION BY S.WATCH_ID ORDER BY S.CHECKED_AT DESC, S.ID DESC) AS ROW_NUMBER_VALUE",
        "  FROM WEB_MONITOR_SNAPSHOT S",
        "  WHERE S.DELETED = 0 AND S.STATUS = 1 AND S.WATCH_ID IN",
        "  <foreach collection='watchIds' item='watchId' open='(' separator=',' close=')'>#{watchId}</foreach>",
        ") latest_checks",
        "WHERE ROW_NUMBER_VALUE = 1",
        "</script>"
    })
    List<DomainWatchLatestCheckRow> selectLatestSuccessfulChecks(@Param("watchIds") List<String> watchIds);

    @Select({
        "<script>",
        "SELECT E.WATCH_ID AS watchId, COUNT(*) AS unreadCount",
        "FROM WEB_USER_NOTIFICATION N INNER JOIN WEB_MONITOR_EVENT E ON E.ID = N.EVENT_ID AND E.DELETED = 0",
        "WHERE N.DELETED = 0 AND N.USER_ID = #{userId} AND N.READ_AT IS NULL AND E.WATCH_ID IN",
        "<foreach collection='watchIds' item='watchId' open='(' separator=',' close=')'>#{watchId}</foreach>",
        "GROUP BY E.WATCH_ID",
        "</script>"
    })
    List<DomainWatchUnreadCountRow> selectUnreadCounts(
            @Param("userId") String userId,
            @Param("watchIds") List<String> watchIds);
}
