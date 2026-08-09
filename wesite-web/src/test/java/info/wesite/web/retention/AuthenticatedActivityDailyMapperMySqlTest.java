package info.wesite.web.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;

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

import info.wesite.core.mapper.AuthenticatedActivityDailyMapper;
import info.wesite.core.mapper.RetentionFactHealthMapper;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class AuthenticatedActivityDailyMapperMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
            .withDatabaseName("whose_domains").withUsername("whose").withPassword("domains");

    private static DataSource dataSource;
    private static AuthenticatedActivityDailyMapper mapper;
    private static RetentionFactHealthMapper health;

    @BeforeAll
    static void configure() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        execute("""
            CREATE TABLE WEB_AUTHENTICATED_ACTIVITY_DAILY (
              USER_ID varchar(32) NOT NULL,
              ACTIVITY_DATE date NOT NULL,
              PRIMARY KEY (USER_ID, ACTIVITY_DATE)
            ) ENGINE=InnoDB
            """);
        execute("""
            CREATE TABLE WEB_RETENTION_FACT_HEALTH (
              FACT_NAME varchar(64) NOT NULL,
              FACT_DATE date NOT NULL,
              SUCCESSFUL_WRITE_COUNT bigint unsigned NOT NULL DEFAULT 0,
              FAILURE_COUNT bigint unsigned NOT NULL DEFAULT 0,
              EXPECTED_FACT_ROWS bigint unsigned NOT NULL DEFAULT 0,
              VERIFICATION_STATUS varchar(16) NOT NULL DEFAULT 'OPEN',
              VERIFIED_AT datetime NULL,
              EXTERNAL_EXPECTED_ROWS bigint unsigned NULL,
              RECONCILIATION_SOURCE varchar(128) NULL,
              RECONCILIATION_ID varchar(128) NULL,
              LAST_SUCCESS_AT datetime NULL,
              LAST_FAILURE_AT datetime NULL,
              PRIMARY KEY (FACT_NAME, FACT_DATE),
              UNIQUE KEY UK_RETENTION_RECONCILIATION_ID (RECONCILIATION_ID)
            ) ENGINE=InnoDB
            """);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        MapperFactoryBean<AuthenticatedActivityDailyMapper> factory =
                new MapperFactoryBean<>(AuthenticatedActivityDailyMapper.class);
        factory.setSqlSessionFactory(sessionFactory);
        factory.afterPropertiesSet();
        mapper = factory.getObject();
        MapperFactoryBean<RetentionFactHealthMapper> healthFactory =
                new MapperFactoryBean<>(RetentionFactHealthMapper.class);
        healthFactory.setSqlSessionFactory(sessionFactory);
        healthFactory.afterPropertiesSet();
        health = healthFactory.getObject();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("DELETE FROM WEB_AUTHENTICATED_ACTIVITY_DAILY");
        execute("DELETE FROM WEB_RETENTION_FACT_HEALTH");
    }

    @Test
    void recordsAtMostOneMinimalFactPerUserAndLocalDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 9);

        assertEquals(1, mapper.recordDaily("user-1", date));
        assertEquals(0, mapper.recordDaily("user-1", date));
        assertEquals(1, mapper.recordDaily("user-2", date));

        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY "
                + "WHERE USER_ID='user-1' AND ACTIVITY_DATE='2026-08-09'"));
    }

    @Test
    void newFactInvalidatesVerifiedAuditInTheHealthUpsert() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 8);
        execute("""
            INSERT INTO WEB_RETENTION_FACT_HEALTH
              (FACT_NAME, FACT_DATE, SUCCESSFUL_WRITE_COUNT, EXPECTED_FACT_ROWS,
               VERIFICATION_STATUS, VERIFIED_AT, EXTERNAL_EXPECTED_ROWS,
               RECONCILIATION_SOURCE, RECONCILIATION_ID)
            VALUES
              ('AUTHENTICATED_ACTIVITY_DAILY', '2026-08-08', 1, 0,
               'VERIFIED', '2026-08-09 00:05:00', 0,
               'auth-gateway', 'gateway-2026-08-08')
            """);

        int inserted = mapper.recordDaily("late-user", date);
        assertEquals(1, inserted);
        health.recordSuccess(date, inserted);

        assertEquals(1, scalar("""
            SELECT COUNT(*) FROM WEB_RETENTION_FACT_HEALTH
            WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY'
              AND FACT_DATE='2026-08-08'
              AND SUCCESSFUL_WRITE_COUNT=2
              AND EXPECTED_FACT_ROWS=1
              AND VERIFICATION_STATUS='OPEN'
              AND VERIFIED_AT IS NULL
              AND EXTERNAL_EXPECTED_ROWS IS NULL
              AND RECONCILIATION_SOURCE IS NULL
              AND RECONCILIATION_ID IS NULL
            """));
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
