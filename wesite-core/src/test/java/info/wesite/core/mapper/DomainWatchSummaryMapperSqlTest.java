package info.wesite.core.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DomainWatchSummaryMapperSqlTest {

    @Test
    void latestEventQueryRanksOneRowPerWatchByTimeThenPersistedRiskThenId() throws Exception {
        String sql = normalizedSql("selectLatestEvents");

        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY WATCH_ID"));
        assertTrue(sql.contains("ORDER BY OCCURRED_AT DESC"));
        assertTrue(sql.contains("WHEN 'CRITICAL' THEN 4"));
        assertTrue(sql.contains("WHEN 'HIGH' THEN 3"));
        assertTrue(sql.contains("WHEN 'MEDIUM' THEN 2"));
        assertTrue(sql.contains("WHEN 'LOW' THEN 1"));
        assertTrue(sql.contains("ID DESC"));
        assertTrue(sql.contains("WHERE ROW_NUMBER_VALUE = 1"));
    }

    @Test
    void aggregateQueriesReturnOnlyTheColumnsNeededByTheWatchlist() throws Exception {
        String checks = normalizedSql("selectLatestSuccessfulChecks");
        String unread = normalizedSql("selectUnreadCounts");

        assertTrue(checks.contains("ROW_NUMBER() OVER (PARTITION BY WATCH_ID"));
        assertTrue(checks.contains("CHECKED_AT AS lastSuccessfulCheck"));
        assertTrue(unread.contains("COUNT(*) AS unreadCount"));
        assertTrue(unread.contains("USER_ID = #{userId}"));
        assertTrue(unread.contains("READ_AT IS NULL"));
    }

    private static String selectSql(String methodName) throws Exception {
        Method method = Arrays.stream(DomainWatchSummaryMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
    }

    private static String normalizedSql(String methodName) throws Exception {
        return selectSql(methodName).replace("E.", "").replace("S.", "").replace("N.", "");
    }
}
