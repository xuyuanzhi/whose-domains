package info.wesite.core.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.AuthenticatedActivityDaily;

public interface AuthenticatedActivityDailyMapper extends BaseMapper<AuthenticatedActivityDaily> {

    @Insert("""
        INSERT INTO WEB_AUTHENTICATED_ACTIVITY_DAILY
          (ID, USER_ID, ACTIVITY_DATE, CREATE_TIME)
        VALUES
          (LOWER(REPLACE(UUID(), '-', '')), #{userId}, #{activityDate}, CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE ACTIVITY_DATE = VALUES(ACTIVITY_DATE)
        """)
    int recordDaily(@Param("userId") String userId, @Param("activityDate") LocalDate activityDate);
}
