package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import info.wesite.core.mapper.DomainWatchSummaryMapper;
import info.wesite.core.mapper.model.DomainWatchLatestEventRow;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class DomainWatchSummaryMapperMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
            .withDatabaseName("whose_domains")
            .withUsername("whose")
            .withPassword("domains");

    private static DataSource dataSource;
    private static DomainWatchSummaryMapper mapper;

    @BeforeAll
    static void configureDatabase() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE WEB_MONITOR_EVENT (
                  ID varchar(32) NOT NULL PRIMARY KEY,
                  DELETED smallint DEFAULT 0,
                  WATCH_ID varchar(32) NOT NULL,
                  EVENT_TYPE varchar(64) NOT NULL,
                  RISK varchar(16),
                  NEW_VALUE text,
                  OCCURRED_AT datetime NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
        MybatisSqlSessionFactoryBean sessionFactoryBean = new MybatisSqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(dataSource);
        sessionFactoryBean.afterPropertiesSet();
        SqlSessionFactory sessionFactory = sessionFactoryBean.getObject();
        MapperFactoryBean<DomainWatchSummaryMapper> factory = new MapperFactoryBean<>(DomainWatchSummaryMapper.class);
        factory.setSqlSessionFactory(sessionFactory);
        factory.afterPropertiesSet();
        mapper = factory.getObject();
    }

    @BeforeEach
    void resetRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM WEB_MONITOR_EVENT");
        }
    }

    @Test
    void sameTimestampLowAndCriticalEventsSelectThePersistedCriticalRisk() throws Exception {
        String occurredAt = Instant.parse("2026-08-09T12:00:00Z").toString().replace("T", " ").replace("Z", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO WEB_MONITOR_EVENT (ID, DELETED, WATCH_ID, EVENT_TYPE, RISK, NEW_VALUE, OCCURRED_AT) "
                    + "VALUES ('event-low', 0, 'watch-1', 'WEBSITE_DOWN', 'LOW', 'low signal', '" + occurredAt + "')");
            statement.executeUpdate("INSERT INTO WEB_MONITOR_EVENT (ID, DELETED, WATCH_ID, EVENT_TYPE, RISK, NEW_VALUE, OCCURRED_AT) "
                    + "VALUES ('event-critical', 0, 'watch-1', 'WEBSITE_DOWN', 'CRITICAL', 'critical signal', '" + occurredAt + "')");
        }

        List<DomainWatchLatestEventRow> rows = mapper.selectLatestEvents(List.of("watch-1"));

        assertEquals(1, rows.size());
        assertEquals("CRITICAL", rows.get(0).getLatestRisk());
        assertEquals("critical signal", rows.get(0).getLatestEventValue());
    }
}
