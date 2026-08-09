package info.wesite.web.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import info.wesite.core.mapper.DomainWatchNotifyLogMapper;
import info.wesite.core.mapper.NotificationDeliveryBatchMapper;
import info.wesite.core.mapper.UserNotificationMapper;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class NotificationDeliveryCoordinatorMySqlConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-08-09T08:00:00Z");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
        .withDatabaseName("whose_domains")
        .withUsername("whose")
        .withPassword("domains")
        .withCommand("--transaction-isolation=REPEATABLE-READ");

    private static DataSource dataSource;
    private static DataSourceTransactionManager transactionManager;
    private static NotificationDeliveryBatchMapper batchMapper;
    private static UserNotificationMapper notificationMapper;
    private static DomainWatchNotifyLogMapper logMapper;
    private static NotificationDeliveryCoordinator coordinator;

    @BeforeAll
    static void configureDatabase() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        transactionManager = new DataSourceTransactionManager(dataSource);
        createTables();
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        batchMapper = mapper(NotificationDeliveryBatchMapper.class, sessionFactory);
        notificationMapper = mapper(UserNotificationMapper.class, sessionFactory);
        logMapper = mapper(DomainWatchNotifyLogMapper.class, sessionFactory);
        coordinator = new NotificationDeliveryCoordinator(batchMapper, notificationMapper, logMapper);
    }

    @AfterAll
    static void releaseDatabase() {
        coordinator = null;
        logMapper = null;
        notificationMapper = null;
        batchMapper = null;
        transactionManager = null;
        dataSource = null;
    }

    @BeforeEach
    void resetRows() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM WEB_DOMAIN_WATCH_NOTIFY_LOG");
            statement.executeUpdate("DELETE FROM WEB_USER_NOTIFICATION");
            statement.executeUpdate("DELETE FROM WEB_NOTIFICATION_DELIVERY_BATCH");
            statement.executeUpdate("""
                INSERT INTO WEB_USER_NOTIFICATION
                  (ID, STATUS, DELETED, CREATE_TIME, USER_ID, EVENT_ID, TITLE, TARGET_PATH,
                   EMAIL_MODE, EMAIL_STATE, EMAIL_ATTEMPT_COUNT)
                VALUES
                  ('n1', 1, 0, '2026-08-09 06:00:00', 'user-1', 'e1', 'one', '/domain/one',
                   'DAILY_DIGEST', 'QUEUED', 0),
                  ('n2', 1, 0, '2026-08-09 07:00:00', 'user-1', 'e2', 'two', '/domain/two',
                   'DAILY_DIGEST', 'QUEUED', 0)
                """);
        }
    }

    @Test
    void twoWorkersCreateOneDigestBatchAndAtomicallyOwnEveryWindowItem() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.List<Attempt> attempts;
        try {
            Future<Attempt> first = executor.submit(() -> startDigestInTransaction(start));
            Future<Attempt> second = executor.submit(() -> startDigestInTransaction(start));
            start.countDown();
            attempts = java.util.List.of(
                first.get(30, TimeUnit.SECONDS),
                second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertNull(attempts.get(0).error());
        assertNull(attempts.get(1).error());
        assertEquals(1, attempts.stream().filter(attempt -> attempt.claim().isPresent()).count());
        assertEquals(1, count("WEB_NOTIFICATION_DELIVERY_BATCH"));
        assertEquals(1, count("WEB_DOMAIN_WATCH_NOTIFY_LOG"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION WHERE EMAIL_STATE = 'CLAIMED'"));
        assertEquals(1, scalar("SELECT COUNT(DISTINCT DELIVERY_BATCH_ID) FROM WEB_USER_NOTIFICATION"));
    }

    @Test
    void pendingLogFailureRollsBackBatchAndEveryMembershipClaim() throws Exception {
        DomainWatchNotifyLogMapper failingLogMapper = (DomainWatchNotifyLogMapper) Proxy.newProxyInstance(
            DomainWatchNotifyLogMapper.class.getClassLoader(),
            new Class<?>[] {DomainWatchNotifyLogMapper.class},
            (proxy, method, arguments) -> method.getName().equals("insert")
                ? 0
                : invoke(logMapper, method, arguments));
        NotificationDeliveryCoordinator failingCoordinator = new NotificationDeliveryCoordinator(
            batchMapper, notificationMapper, failingLogMapper);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
            failingCoordinator.startDigest(
                "user-1", "DAILY_DIGEST", "2026-08-09", NOW, NOW, NOW.plusSeconds(600))));

        assertEquals(0, count("WEB_NOTIFICATION_DELIVERY_BATCH"));
        assertEquals(0, count("WEB_DOMAIN_WATCH_NOTIFY_LOG"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION "
            + "WHERE EMAIL_STATE = 'QUEUED' AND DELIVERY_BATCH_ID IS NULL"));
    }

    @Test
    void successfulCompletionUpdatesTheExactPendingAttemptAndWholeDigestBatch() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        DeliveryBatchClaim claim = transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", NOW, NOW, NOW.plusSeconds(600)).orElseThrow());

        transaction.executeWithoutResult(status -> coordinator.complete(
            claim,
            true,
            new DeliveryAttemptDetails(null, null, null, "person@example.com", null, null, "subject", null),
            NOW.plusSeconds(10),
            NOW.plusSeconds(300)));

        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE = 'SENT'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION WHERE EMAIL_STATE = 'SENT'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_DOMAIN_WATCH_NOTIFY_LOG "
            + "WHERE SEND_STATUS = 1 AND RETRY_COUNT = 1"));
    }

    private Attempt startDigestInTransaction(CountDownLatch start) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            start.await(10, TimeUnit.SECONDS);
            Optional<DeliveryBatchClaim> claim = transaction.execute(status -> coordinator.startDigest(
                "user-1", "DAILY_DIGEST", "2026-08-09", NOW, NOW, NOW.plusSeconds(600)));
            return new Attempt(claim, null);
        } catch (Exception error) {
            return new Attempt(Optional.empty(), error);
        }
    }

    private static int count(String table) throws SQLException {
        return scalar("SELECT COUNT(*) FROM " + table);
    }

    private static int scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static <T> T mapper(Class<T> type, SqlSessionFactory sessionFactory) throws Exception {
        MapperFactoryBean<T> factory = new MapperFactoryBean<>(type);
        factory.setSqlSessionFactory(sessionFactory);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static void createTables() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE WEB_NOTIFICATION_DELIVERY_BATCH (
                  ID varchar(32) NOT NULL PRIMARY KEY, STATUS smallint DEFAULT 1, DELETED smallint DEFAULT 0,
                  CREATE_BY varchar(50), CREATE_TIME datetime, UPDATE_BY varchar(50), UPDATE_TIME datetime,
                  USER_ID varchar(32) NOT NULL, EMAIL_MODE varchar(32) NOT NULL, WINDOW_KEY varchar(64) NOT NULL,
                  STATE varchar(32) NOT NULL, ATTEMPT_COUNT int NOT NULL DEFAULT 0,
                  CLAIM_TOKEN varchar(64), CLAIM_UNTIL datetime, NEXT_ATTEMPT_AT datetime, COMPLETED_AT datetime,
                  UNIQUE KEY UK_NOTIFICATION_BATCH_USER_MODE_WINDOW (USER_ID, EMAIL_MODE, WINDOW_KEY)
                ) ENGINE=InnoDB
                """);
            statement.execute("""
                CREATE TABLE WEB_USER_NOTIFICATION (
                  ID varchar(32) NOT NULL PRIMARY KEY, STATUS smallint DEFAULT 1, DELETED smallint DEFAULT 0,
                  CREATE_BY varchar(50), CREATE_TIME datetime, UPDATE_BY varchar(50), UPDATE_TIME datetime,
                  USER_ID varchar(32) NOT NULL, EVENT_ID varchar(32) NOT NULL, TITLE varchar(255) NOT NULL,
                  CONTENT text, TARGET_PATH varchar(500) NOT NULL, READ_AT datetime,
                  EMAIL_MODE varchar(32), EMAIL_STATE varchar(32) NOT NULL, EMAIL_ATTEMPT_COUNT int NOT NULL,
                  EMAIL_CLAIM_TOKEN varchar(64), EMAIL_CLAIM_UNTIL datetime, DELIVERY_BATCH_ID varchar(32),
                  EMAILED_AT datetime, UNIQUE KEY UK_USER_NOTIFICATION_USER_EVENT (USER_ID, EVENT_ID)
                ) ENGINE=InnoDB
                """);
            statement.execute("""
                CREATE TABLE WEB_DOMAIN_WATCH_NOTIFY_LOG (
                  ID varchar(32) NOT NULL PRIMARY KEY, STATUS smallint DEFAULT 1, DELETED smallint DEFAULT 0,
                  CREATE_BY varchar(50), CREATE_TIME datetime, UPDATE_BY varchar(50), UPDATE_TIME datetime,
                  NOTIFICATION_ID varchar(32), BATCH_ID varchar(32), EVENT_ID varchar(32), DELIVERY_MODE varchar(32),
                  SUBJECT varchar(255), WATCH_ID varchar(32), TO_EMAIL varchar(255), DOMAIN_NAME varchar(255),
                  DAYS_LEFT int, SENT_AT datetime, SEND_STATUS int, ERROR_MSG text, RETRY_COUNT int,
                  UNIQUE KEY UK_NOTIFY_LOG_BATCH_RETRY (BATCH_ID, RETRY_COUNT)
                ) ENGINE=InnoDB
                """);
        }
    }

    private record Attempt(Optional<DeliveryBatchClaim> claim, Throwable error) {
    }

}
