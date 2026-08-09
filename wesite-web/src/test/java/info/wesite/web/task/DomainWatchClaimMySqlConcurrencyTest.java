package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import info.wesite.core.entity.DomainWatch;
import info.wesite.core.mapper.DomainWatchMapper;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class DomainWatchClaimMySqlConcurrencyTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
        .withDatabaseName("whose_domains").withUsername("whose").withPassword("domains");

    private static DataSource dataSource;
    private static DomainWatchMapper mapper;

    @BeforeAll
    static void configure() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE WEB_DOMAIN_WATCH (
                  ID varchar(32) PRIMARY KEY, STATUS smallint NOT NULL, DELETED smallint NOT NULL DEFAULT 0,
                  USER_ID varchar(32), DOMAIN_NAME varchar(255), DOMAIN_ID varchar(32), REGISTRAR varchar(255),
                  EXPIRY_DATE_TEXT varchar(255), EXPIRY_DATE datetime, LAST_CHECK_TIME datetime,
                  UPDATE_TIME datetime, SCAN_CLAIM_TOKEN varchar(64), SCAN_CLAIM_UNTIL datetime
                ) ENGINE=InnoDB
                """);
        }
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        MapperFactoryBean<DomainWatchMapper> factory = new MapperFactoryBean<>(DomainWatchMapper.class);
        factory.setSqlSessionFactory(sessionFactory);
        factory.afterPropertiesSet();
        mapper = factory.getObject();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("DELETE FROM WEB_DOMAIN_WATCH");
        execute("INSERT INTO WEB_DOMAIN_WATCH (ID, STATUS, DELETED, USER_ID, DOMAIN_NAME) "
            + "VALUES ('watch-1', 1, 0, 'user-1', 'example.com')");
    }

    @Test
    void onlyOneWorkerClaimsAndAnExpiredLeaseCanBeRecoveredWithCompletionCas() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> attempts;
        try {
            attempts = List.of(
                executor.submit(() -> claim(start, "worker-a")),
                executor.submit(() -> claim(start, "worker-b")));
            start.countDown();
            assertEquals(1, attempts.get(0).get(20, TimeUnit.SECONDS)
                + attempts.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        execute("UPDATE WEB_DOMAIN_WATCH SET SCAN_CLAIM_UNTIL = '2026-08-09 00:00:00'");
        Date recoveredAt = java.sql.Timestamp.valueOf("2026-08-09 00:01:00");
        Date recoveredUntil = java.sql.Timestamp.valueOf("2026-08-09 00:06:00");
        assertEquals(1, mapper.claimScan("watch-1", "worker-c", recoveredUntil, recoveredAt));

        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setDomainId("domain-1");
        watch.setRegistrar("Registrar");
        watch.setLastCheckTime(recoveredAt);
        watch.setUpdateTime(recoveredAt);
        assertEquals(0, mapper.completeScan(watch, "stale-worker"));
        assertEquals(1, mapper.completeScan(watch, "worker-c"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM WEB_DOMAIN_WATCH WHERE SCAN_CLAIM_TOKEN IS NOT NULL"));
    }

    private static int claim(CountDownLatch start, String token) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        Date now = java.sql.Timestamp.valueOf("2026-08-09 00:00:01");
        Date until = java.sql.Timestamp.valueOf("2026-08-09 00:05:01");
        return mapper.claimScan("watch-1", token, until, now);
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
