package info.wesite.core.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface RetentionFactHealthMapper {
    @Insert("""
        INSERT INTO WEB_RETENTION_FACT_HEALTH
          (FACT_NAME, FACT_DATE, SUCCESSFUL_WRITE_COUNT, EXPECTED_FACT_ROWS, LAST_SUCCESS_AT)
        VALUES ('AUTHENTICATED_ACTIVITY_DAILY', #{date}, 1, #{inserted}, CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE SUCCESSFUL_WRITE_COUNT = SUCCESSFUL_WRITE_COUNT + 1,
          EXPECTED_FACT_ROWS = EXPECTED_FACT_ROWS + VALUES(EXPECTED_FACT_ROWS),
          VERIFIED_AT = IF(VALUES(EXPECTED_FACT_ROWS) > 0, NULL, VERIFIED_AT),
          EXTERNAL_EXPECTED_ROWS = IF(VALUES(EXPECTED_FACT_ROWS) > 0, NULL, EXTERNAL_EXPECTED_ROWS),
          RECONCILIATION_SOURCE = IF(VALUES(EXPECTED_FACT_ROWS) > 0, NULL, RECONCILIATION_SOURCE),
          RECONCILIATION_ID = IF(VALUES(EXPECTED_FACT_ROWS) > 0, NULL, RECONCILIATION_ID),
          VERIFICATION_STATUS = IF(VALUES(EXPECTED_FACT_ROWS) > 0, 'OPEN', VERIFICATION_STATUS),
          LAST_SUCCESS_AT = CURRENT_TIMESTAMP
        """)
    int recordSuccess(@Param("date") LocalDate date, @Param("inserted") int inserted);

    @Insert("""
        INSERT INTO WEB_RETENTION_FACT_HEALTH
          (FACT_NAME, FACT_DATE, FAILURE_COUNT, LAST_FAILURE_AT)
        VALUES ('AUTHENTICATED_ACTIVITY_DAILY', #{date}, 1, CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE FAILURE_COUNT = FAILURE_COUNT + 1, LAST_FAILURE_AT = CURRENT_TIMESTAMP
        """)
    int recordFailure(@Param("date") LocalDate date);

    @Update("""
        UPDATE WEB_RETENTION_FACT_COLLECTION
        SET COLLECTION_STARTED_ON = COALESCE(COLLECTION_STARTED_ON, #{date}), UPDATE_TIME = CURRENT_TIMESTAMP
        WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY'
        """)
    int activate(@Param("date") LocalDate date);
}
