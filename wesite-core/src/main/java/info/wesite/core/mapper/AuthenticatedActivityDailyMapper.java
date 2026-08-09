package info.wesite.core.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface AuthenticatedActivityDailyMapper {

    @Insert("""
        INSERT IGNORE INTO WEB_AUTHENTICATED_ACTIVITY_DAILY
          (USER_ID, ACTIVITY_DATE)
        VALUES
          (#{userId}, #{activityDate})
        """)
    int recordDaily(@Param("userId") String userId, @Param("activityDate") LocalDate activityDate);
}
