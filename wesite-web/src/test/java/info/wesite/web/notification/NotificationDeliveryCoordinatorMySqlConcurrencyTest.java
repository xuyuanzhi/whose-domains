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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private static NotificationCancellationService cancellationService;

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
        cancellationService = new NotificationCancellationService(batchMapper, notificationMapper);
    }

    @AfterAll
    static void releaseDatabase() {
        coordinator = null;
        cancellationService = null;
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
            statement.executeUpdate("DELETE FROM WEB_MONITOR_EVENT");
            statement.executeUpdate("DELETE FROM WEB_DOMAIN_WATCH");
            statement.executeUpdate("""
                INSERT INTO WEB_DOMAIN_WATCH (ID, USER_ID, NOTIFY_TYPE, NOTIFY_EMAIL)
                VALUES ('watch-1', 'user-1', 3, 'person@example.com'),
                       ('watch-2', 'user-1', 3, 'person@example.com')
                """);
            statement.executeUpdate("""
                INSERT INTO WEB_MONITOR_EVENT (ID, WATCH_ID, EVENT_TYPE)
                VALUES ('e1', 'watch-1', 'SSL_EXPIRING'),
                       ('e2', 'watch-2', 'DNS_CHANGED')
                """);
            statement.executeUpdate("""
                INSERT INTO WEB_USER_NOTIFICATION
                  (ID, STATUS, DELETED, CREATE_TIME, USER_ID, EVENT_ID, TITLE, TARGET_PATH,
                   EMAIL_MODE, EMAIL_STATE, EMAIL_ATTEMPT_COUNT, RECIPIENT_EMAIL)
                VALUES
                  ('n1', 1, 0, '2026-08-09 06:00:00', 'user-1', 'e1', 'one', '/domain/one',
                   'DAILY_DIGEST', 'QUEUED', 0, 'person@example.com'),
                  ('n2', 1, 0, '2026-08-09 07:00:00', 'user-1', 'e2', 'two', '/domain/two',
                   'DAILY_DIGEST', 'QUEUED', 0, 'person@example.com')
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
                "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
                NOW, NOW, NOW.plusSeconds(600))));

        assertEquals(0, count("WEB_NOTIFICATION_DELIVERY_BATCH"));
        assertEquals(0, count("WEB_DOMAIN_WATCH_NOTIFY_LOG"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION "
            + "WHERE EMAIL_STATE = 'QUEUED' AND DELIVERY_BATCH_ID IS NULL"));
    }

    @Test
    void successfulCompletionUpdatesTheExactPendingAttemptAndWholeDigestBatch() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        DeliveryBatchClaim claim = transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
            NOW, NOW, NOW.plusSeconds(600)).orElseThrow());

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

    @Test
    void deletingEveryInAppRowDoesNotBreakFrozenMembersOrBatchTerminalization() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        DeliveryBatchClaim claim = transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
            NOW, NOW, NOW.plusSeconds(600)).orElseThrow());

        execute("UPDATE WEB_USER_NOTIFICATION SET DELETED = 1 WHERE USER_ID = 'user-1'");
        assertEquals(2, notificationMapper.selectBatchMembers(claim.batchId()).size());
        transaction.executeWithoutResult(status -> coordinator.complete(
            claim, true,
            new DeliveryAttemptDetails(null, null, null, "person@example.com", null, null, "subject", null),
            NOW.plusSeconds(10), NOW.plusSeconds(300)));

        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE = 'SENT'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION "
            + "WHERE DELETED = 1 AND EMAIL_STATE = 'SENT'"));
    }

    @Test
    void failedBatchCanRetryAfterEveryInAppRowWasDeleted() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        DeliveryBatchClaim first = transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
            NOW, NOW, NOW.plusSeconds(600)).orElseThrow());
        transaction.executeWithoutResult(status -> coordinator.complete(
            first, false,
            new DeliveryAttemptDetails(null, null, null, "person@example.com", null, null, "subject", "smtp"),
            NOW.plusSeconds(10), NOW.plusSeconds(300)));
        execute("UPDATE WEB_USER_NOTIFICATION SET DELETED = 1 WHERE USER_ID = 'user-1'");

        DeliveryBatchClaim retry = transaction.execute(status -> coordinator.retryNext(
            "DAILY_DIGEST", NOW.plusSeconds(301), NOW.plusSeconds(901)).orElseThrow());
        assertEquals(2, retry.attempt());
        assertEquals(2, notificationMapper.selectBatchMembers(retry.batchId()).size());
        transaction.executeWithoutResult(status -> coordinator.complete(
            retry, true,
            new DeliveryAttemptDetails(null, null, null, "person@example.com", null, null, "subject", null),
            NOW.plusSeconds(302), NOW.plusSeconds(600)));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE = 'SENT'"));
    }

    @Test
    void oneDigestWindowCreatesSeparateFrozenBatchesForSeparateWatchRecipients() throws Exception {
        execute("UPDATE WEB_USER_NOTIFICATION SET RECIPIENT_EMAIL = 'other@example.com' WHERE ID = 'n2'");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        DeliveryBatchClaim first = transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
            NOW, NOW, NOW.plusSeconds(600)).orElseThrow());
        DeliveryBatchClaim second = transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "other@example.com",
            NOW, NOW, NOW.plusSeconds(600)).orElseThrow());

        assertEquals("person@example.com", first.recipientEmail());
        assertEquals("other@example.com", second.recipientEmail());
        assertEquals(2, count("WEB_NOTIFICATION_DELIVERY_BATCH"));
        assertEquals(2, scalar("SELECT COUNT(DISTINCT DELIVERY_BATCH_ID) FROM WEB_USER_NOTIFICATION"));
    }

    @Test
    void expiredClaimedPreferenceCancellationEndsInAppOnlyWithoutARetry() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
            NOW, NOW, NOW.plusSeconds(600)).orElseThrow());
        execute("UPDATE WEB_NOTIFICATION_DELIVERY_BATCH SET CANCELLATION_REQUESTED = 1");

        transaction.executeWithoutResult(status -> coordinator.finalizeExpiredCancelled(
            "DAILY_DIGEST", NOW.plusSeconds(601)));

        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE = 'CANCELLED'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION WHERE EMAIL_STATE = 'IN_APP_ONLY'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_DOMAIN_WATCH_NOTIFY_LOG WHERE SEND_STATUS = 2"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE IN ('CLAIMED','FAILED')"));
    }

    @Test
    void claimedWatchCancellationThenSmtpFailureCancelsTheWholeMixedDigestWithoutRetry() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        DeliveryBatchClaim claim = transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
            NOW, NOW, NOW.plusSeconds(600)).orElseThrow());

        transaction.executeWithoutResult(status -> cancellationService.cancelForWatch(
            "user-1", "watch-1", NOW.plusSeconds(1)));

        assertEquals(1, scalar("SELECT CANCELLATION_REQUESTED FROM WEB_NOTIFICATION_DELIVERY_BATCH"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION WHERE EMAIL_STATE = 'CLAIMED'"));

        transaction.executeWithoutResult(status -> coordinator.complete(
            claim, false,
            new DeliveryAttemptDetails(null, null, null, "person@example.com", null, null, "subject", "smtp"),
            NOW.plusSeconds(2), NOW.plusSeconds(300)));

        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE = 'CANCELLED'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION WHERE EMAIL_STATE = 'IN_APP_ONLY'"));
        assertEquals(0, transaction.execute(status -> coordinator.retryNext(
            "DAILY_DIGEST", NOW.plusSeconds(301), NOW.plusSeconds(901))).stream().count());
    }

    @Test
    void claimedCategoryCancellationAtLeaseExpiryCancelsTheWholeMixedDigestWithoutRetry() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
            NOW, NOW, NOW.plusSeconds(600)).orElseThrow());

        transaction.executeWithoutResult(status -> cancellationService.cancelForEventTypes(
            "user-1", java.util.Set.of("SSL_EXPIRING"), NOW.plusSeconds(1)));
        transaction.executeWithoutResult(status -> coordinator.finalizeExpiredCancelled(
            "DAILY_DIGEST", NOW.plusSeconds(601)));

        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE = 'CANCELLED'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION WHERE EMAIL_STATE = 'IN_APP_ONLY'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE IN ('CLAIMED','FAILED')"));
    }

    @Test
    void cancellationSqlFailureRollsBackTheWatchSettingAndNotificationStateTogether() throws Exception {
        NotificationDeliveryBatchMapper failingBatchMapper = (NotificationDeliveryBatchMapper) Proxy.newProxyInstance(
            NotificationDeliveryBatchMapper.class.getClassLoader(),
            new Class<?>[] {NotificationDeliveryBatchMapper.class},
            (proxy, method, arguments) -> method.getName().equals("selectForWatchForUpdate")
                ? throwSqlFailure()
                : invoke(batchMapper, method, arguments));
        NotificationCancellationService failingCancellation = new NotificationCancellationService(
            failingBatchMapper, notificationMapper);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE WEB_DOMAIN_WATCH SET NOTIFY_TYPE = 0, NOTIFY_EMAIL = NULL WHERE ID = 'watch-1'");
            failingCancellation.cancelForWatch("user-1", "watch-1", NOW.plusSeconds(1));
        }));

        assertEquals(3, scalar("SELECT NOTIFY_TYPE FROM WEB_DOMAIN_WATCH WHERE ID = 'watch-1'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION WHERE ID = 'n1' AND EMAIL_STATE = 'QUEUED'"));
    }

    @Test
    void failedBatchCancellationRacingRetryHasNoDeadlockAndCannotEscapeCancellation() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        DeliveryBatchClaim first = transaction.execute(status -> coordinator.startDigest(
            "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
            NOW, NOW, NOW.plusSeconds(60)).orElseThrow());
        transaction.executeWithoutResult(status -> coordinator.complete(
            first, false,
            new DeliveryAttemptDetails(null, null, null, "person@example.com", null, null, "subject", "smtp"),
            NOW.plusSeconds(1), NOW.plusSeconds(2)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancel = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    cancellationService.cancelForWatch("user-1", "watch-1", NOW.plusSeconds(3)));
                return null;
            });
            Future<?> retry = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                new TransactionTemplate(transactionManager).execute(status ->
                    coordinator.retryNext("DAILY_DIGEST", NOW.plusSeconds(3), NOW.plusSeconds(30)));
                return null;
            });
            start.countDown();
            cancel.get(20, TimeUnit.SECONDS);
            retry.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        transaction.executeWithoutResult(status ->
            coordinator.finalizeExpiredCancelled("DAILY_DIGEST", NOW.plusSeconds(31)));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE STATE = 'CANCELLED'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION "
            + "WHERE EMAIL_STATE = 'IN_APP_ONLY' AND EMAIL_MODE = 'IN_APP_ONLY'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH "
            + "WHERE STATE IN ('FAILED','CLAIMED')"));
    }

    private Attempt startDigestInTransaction(CountDownLatch start) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            start.await(10, TimeUnit.SECONDS);
            Optional<DeliveryBatchClaim> claim = transaction.execute(status -> coordinator.startDigest(
                "user-1", "DAILY_DIGEST", "2026-08-09", "person@example.com",
                NOW, NOW, NOW.plusSeconds(600)));
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

    private static void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
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

    private static Object throwSqlFailure() {
        throw new IllegalStateException("cancellation SQL failed");
    }

    private static void createTables() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE WEB_DOMAIN_WATCH (
                  ID varchar(32) NOT NULL PRIMARY KEY, USER_ID varchar(32) NOT NULL,
                  NOTIFY_TYPE int NOT NULL, NOTIFY_EMAIL varchar(254)
                ) ENGINE=InnoDB
                """);
            statement.execute("""
                CREATE TABLE WEB_MONITOR_EVENT (
                  ID varchar(32) NOT NULL PRIMARY KEY, WATCH_ID varchar(32) NOT NULL,
                  EVENT_TYPE varchar(64) NOT NULL
                ) ENGINE=InnoDB
                """);
            statement.execute("""
                CREATE TABLE WEB_NOTIFICATION_DELIVERY_BATCH (
                  ID varchar(32) NOT NULL PRIMARY KEY, STATUS smallint DEFAULT 1, DELETED smallint DEFAULT 0,
                  CREATE_BY varchar(50), CREATE_TIME datetime, UPDATE_BY varchar(50), UPDATE_TIME datetime,
                  USER_ID varchar(32) NOT NULL, EMAIL_MODE varchar(32) NOT NULL, WINDOW_KEY varchar(64) NOT NULL,
                  RECIPIENT_EMAIL varchar(254) NOT NULL, CANCELLATION_REQUESTED smallint NOT NULL DEFAULT 0,
                  STATE varchar(32) NOT NULL, ATTEMPT_COUNT int NOT NULL DEFAULT 0,
                  CLAIM_TOKEN varchar(64), CLAIM_UNTIL datetime, NEXT_ATTEMPT_AT datetime, COMPLETED_AT datetime,
                  UNIQUE KEY UK_NOTIFICATION_BATCH_USER_MODE_WINDOW
                    (USER_ID, EMAIL_MODE, RECIPIENT_EMAIL, WINDOW_KEY)
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
                  RECIPIENT_EMAIL varchar(254), EMAILED_AT datetime,
                  UNIQUE KEY UK_USER_NOTIFICATION_USER_EVENT (USER_ID, EVENT_ID)
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
