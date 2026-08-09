package info.wesite.web.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mapper.DomainWatchMapper;
import info.wesite.core.mapper.DomainWatchNotifyLogMapper;
import info.wesite.core.mapper.NotificationDeliveryBatchMapper;
import info.wesite.core.mapper.NotificationPreferenceMapper;
import info.wesite.core.mapper.UserMapper;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.service.NotificationPreferenceService;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.service.impl.NotificationPreferenceServiceImpl;
import info.wesite.core.service.impl.UserNotificationServiceImpl;
import info.wesite.core.utils.RandomUtils;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class NotificationPreferenceMySqlConcurrencyTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
        .withDatabaseName("whose_domains")
        .withUsername("whose")
        .withPassword("domains")
        .withCommand("--transaction-isolation=REPEATABLE-READ");

    private static DataSource dataSource;
    private static DataSourceTransactionManager transactions;
    private static UserMapper users;
    private static NotificationPreferenceMapper preferences;
    private static DomainWatchMapper watches;
    private static UserNotificationMapper notifications;
    private static NotificationDeliveryBatchMapper batches;
    private static DomainWatchNotifyLogMapper logs;
    private static NotificationPreferenceService preferenceService;
    private static UserNotificationService notificationService;

    @BeforeAll
    static void configure() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        transactions = new DataSourceTransactionManager(dataSource);
        createTables();
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.afterPropertiesSet();
        SqlSessionFactory sessions = factory.getObject();
        users = mapper(UserMapper.class, sessions);
        preferences = mapper(NotificationPreferenceMapper.class, sessions);
        watches = mapper(DomainWatchMapper.class, sessions);
        notifications = mapper(UserNotificationMapper.class, sessions);
        batches = mapper(NotificationDeliveryBatchMapper.class, sessions);
        logs = mapper(DomainWatchNotifyLogMapper.class, sessions);
        NotificationPreferenceServiceImpl preferenceImpl = new NotificationPreferenceServiceImpl();
        ReflectionTestUtils.setField(preferenceImpl, "baseMapper", preferences);
        preferenceService = preferenceImpl;
        UserNotificationServiceImpl notificationImpl = new UserNotificationServiceImpl();
        ReflectionTestUtils.setField(notificationImpl, "baseMapper", notifications);
        notificationService = notificationImpl;
    }

    @AfterAll
    static void release() {
        dataSource = null;
        transactions = null;
    }

    @BeforeEach
    void reset() throws Exception {
        execute("DELETE FROM WEB_DOMAIN_WATCH_NOTIFY_LOG");
        execute("DELETE FROM WEB_USER_NOTIFICATION");
        execute("DELETE FROM WEB_MONITOR_EVENT");
        execute("DELETE FROM WEB_NOTIFICATION_PREFERENCE");
        execute("DELETE FROM WEB_NOTIFICATION_DELIVERY_BATCH");
        execute("DELETE FROM WEB_DOMAIN_WATCH");
        execute("DELETE FROM SYS_USER");
        execute("INSERT INTO SYS_USER (ID, DELETED) VALUES ('user-1', 0)");
        execute("INSERT INTO WEB_DOMAIN_WATCH "
            + "(ID, STATUS, DELETED, USER_ID, DOMAIN_NAME, NOTIFY_TYPE, NOTIFY_EMAIL) VALUES "
            + "('watch-1', 1, 0, 'user-1', 'example.com', 3, 'old@example.com')");
        execute("INSERT INTO WEB_MONITOR_EVENT (ID, WATCH_ID, EVENT_TYPE) "
            + "VALUES ('event-1', 'watch-1', 'DNS_CHANGED')");
        execute("INSERT INTO WEB_USER_NOTIFICATION "
            + "(ID, STATUS, DELETED, USER_ID, EVENT_ID, TITLE, TARGET_PATH, EMAIL_STATE) VALUES "
            + "('notification-1', 1, 0, 'user-1', 'event-1', 'dns', '/domain/example.com', 'IN_APP_ONLY')");
    }

    @Test
    void missingPreferencePublisherCannotQueueAfterInAppOnlyCancellationCommits() throws Exception {
        racePolicyChange(true);
    }

    @Test
    void publisherCannotQueueDisabledCategoryAfterCancellationCommits() throws Exception {
        execute("INSERT INTO WEB_NOTIFICATION_PREFERENCE "
            + "(ID, STATUS, DELETED, USER_ID, EMAIL_MODE, DOMAIN_EXPIRY_ENABLED, SSL_EXPIRY_ENABLED, "
            + "DOMAIN_STATUS_ENABLED, DNS_CHANGE_ENABLED, WEBSITE_AVAILABILITY_ENABLED) VALUES "
            + "('preference-1', 1, 0, 'user-1', 'IMMEDIATE', 1, 1, 1, 1, 1)");
        racePolicyChange(false);
    }

    @Test
    void initialClaimSerializesWithCancel() throws Exception { raceInitialClaim("cancel"); }

    @Test
    void initialClaimSerializesWithNotifyNone() throws Exception { raceInitialClaim("none"); }

    @Test
    void initialClaimSerializesWithCategoryDisable() throws Exception { raceInitialClaim("category"); }

    @Test
    void initialClaimSerializesWithEmailChange() throws Exception { raceInitialClaim("email"); }

    private void raceInitialClaim(String change) throws Exception {
        execute("UPDATE WEB_USER_NOTIFICATION SET EMAIL_MODE='IMMEDIATE_EMAIL', EMAIL_STATE='QUEUED', "
            + "RECIPIENT_EMAIL='old@example.com' WHERE ID='notification-1'");
        CountDownLatch claimOwnsUserLock = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        UserNotificationMapper gated = gateImmediateRead(notifications, claimOwnsUserLock, releaseClaim);
        NotificationPolicyLock policy = new NotificationPolicyLock(users, preferences);
        NotificationDeliveryCoordinator claimCoordinator = new NotificationDeliveryCoordinator(
            batches, gated, logs, policy);
        NotificationCancellationService cancellation = new NotificationCancellationService(
            batches, notifications, policy, watches);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> claimant = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(s ->
                claimCoordinator.startImmediate("notification-1", Instant.now(), Instant.now().plusSeconds(60))));
            claimOwnsUserLock.await(10, TimeUnit.SECONDS);
            Future<?> closer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
                policy.lockUser("user-1");
                try {
                    if ("none".equals(change)) execute("UPDATE WEB_DOMAIN_WATCH SET NOTIFY_TYPE=0 WHERE ID='watch-1'");
                    if ("email".equals(change)) execute("UPDATE WEB_DOMAIN_WATCH SET NOTIFY_EMAIL='new@example.com' WHERE ID='watch-1'");
                    if ("category".equals(change)) {
                        execute("INSERT INTO WEB_NOTIFICATION_PREFERENCE (ID,STATUS,DELETED,USER_ID,EMAIL_MODE,DNS_CHANGE_ENABLED) VALUES ('p-close',1,0,'user-1','IMMEDIATE',0)");
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
                if ("category".equals(change)) cancellation.cancelForEventTypes("user-1", Set.of("DNS_CHANGED"), Instant.now());
                else if ("cancel".equals(change)) cancellation.cancelAllForUser("user-1", Instant.now());
                else cancellation.cancelForWatch("user-1", "watch-1", Instant.now());
            }));
            Thread.sleep(150);
            assertFalse(closer.isDone(), "policy change must wait for initial claim user mutex");
            releaseClaim.countDown();
            claimant.get(20, TimeUnit.SECONDS);
            closer.get(20, TimeUnit.SECONDS);
        } finally {
            releaseClaim.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        claimCoordinator.finalizeExpiredCancelled("IMMEDIATE_EMAIL", Instant.now().plusSeconds(120));
        assertEquals(0, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION WHERE EMAIL_STATE='CLAIMED' AND RECIPIENT_EMAIL='old@example.com'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_NOTIFICATION_DELIVERY_BATCH WHERE RECIPIENT_EMAIL='old@example.com' AND STATE='CANCELLED'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_DOMAIN_WATCH_NOTIFY_LOG"));
    }

    private static UserNotificationMapper gateImmediateRead(UserNotificationMapper delegate,
            CountDownLatch reached, CountDownLatch release) {
        return (UserNotificationMapper) Proxy.newProxyInstance(UserNotificationMapper.class.getClassLoader(),
            new Class<?>[] { UserNotificationMapper.class }, (proxy, method, args) -> {
                if (method.getName().equals("selectImmediateForUpdate")) {
                    reached.countDown(); release.await(10, TimeUnit.SECONDS);
                }
                try { return method.invoke(delegate, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
    }

    private void racePolicyChange(boolean global) throws Exception {
        CountDownLatch publisherHasPolicyLock = new CountDownLatch(1);
        CountDownLatch releasePublisher = new CountDownLatch(1);
        NotificationPreferenceMapper gatedPreferences = gateFirstPreferenceRead(
            preferences, publisherHasPolicyLock, releasePublisher);
        NotificationPolicyLock publisherPolicy = new NotificationPolicyLock(users, gatedPreferences);
        NotificationPolicyLock currentPolicy = new NotificationPolicyLock(users, preferences);
        NotificationDispatcher dispatcher = new NotificationDispatcher(
            preferenceService, notificationService, watches,
            new NotificationPreferenceResolver(), publisherPolicy);
        NotificationCancellationService cancellation = new NotificationCancellationService(
            batches, notifications, currentPolicy, watches);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publisher = executor.submit(() -> {
                new TransactionTemplate(transactions).executeWithoutResult(status -> dispatcher.dispatch(
                    event(), notification(), watch(), null));
                return null;
            });
            publisherHasPolicyLock.await(10, TimeUnit.SECONDS);
            Future<?> closer = executor.submit(() -> {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    NotificationPreference preference = currentPolicy.lockCurrent("user-1");
                    boolean existing = preference.getId() != null;
                    if (global) {
                        preference.setEmailMode(NotificationPreference.MODE_IN_APP_ONLY);
                    } else {
                        preference.setDnsChangeEnabled(false);
                    }
                    if (existing) {
                        preferenceService.updateById(preference);
                    } else {
                        initialize(preference);
                        preferenceService.save(preference);
                    }
                    if (global) {
                        cancellation.cancelAllForUser("user-1", Instant.now());
                    } else {
                        cancellation.cancelForEventTypes("user-1", Set.of("DNS_CHANGED"), Instant.now());
                    }
                });
                return null;
            });
            Thread.sleep(200);
            assertFalse(closer.isDone(), "policy close must wait for the publisher's stable user mutex");
            releasePublisher.countDown();
            publisher.get(20, TimeUnit.SECONDS);
            closer.get(20, TimeUnit.SECONDS);
        } finally {
            releasePublisher.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_USER_NOTIFICATION "
            + "WHERE USER_ID = 'user-1' AND EMAIL_STATE = 'IN_APP_ONLY' "
            + "AND EMAIL_MODE = 'IN_APP_ONLY'"));
    }

    private static NotificationPreferenceMapper gateFirstPreferenceRead(
            NotificationPreferenceMapper delegate,
            CountDownLatch reached,
            CountDownLatch release) {
        return (NotificationPreferenceMapper) Proxy.newProxyInstance(
            NotificationPreferenceMapper.class.getClassLoader(),
            new Class<?>[] {NotificationPreferenceMapper.class},
            (proxy, method, arguments) -> {
                Object result = invoke(delegate, method, arguments);
                if (method.getName().equals("selectByUserIdForUpdate")) {
                    reached.countDown();
                    release.await(10, TimeUnit.SECONDS);
                }
                return result;
            });
    }

    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static MonitorEvent event() {
        MonitorEvent event = new MonitorEvent();
        event.setEventType("DNS_CHANGED");
        event.setRisk("MEDIUM");
        return event;
    }

    private static UserNotification notification() {
        UserNotification notification = new UserNotification();
        notification.setId("notification-1");
        notification.setUserId("user-1");
        return notification;
    }

    private static DomainWatch watch() {
        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setUserId("user-1");
        return watch;
    }

    private static void initialize(NotificationPreference preference) {
        preference.setId(RandomUtils.generateId());
        preference.setStatus(BaseEntity.STATUS_ACTIVE);
        preference.setDeleted(0);
        preference.setCreateTime(new Date());
    }

    private static <T> T mapper(Class<T> type, SqlSessionFactory sessions) throws Exception {
        MapperFactoryBean<T> factory = new MapperFactoryBean<>(type);
        factory.setSqlSessionFactory(sessions);
        factory.afterPropertiesSet();
        return factory.getObject();
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

    private static void createTables() throws Exception {
        execute("CREATE TABLE SYS_USER (ID varchar(32) PRIMARY KEY, DELETED smallint NOT NULL DEFAULT 0) ENGINE=InnoDB");
        execute("CREATE TABLE WEB_DOMAIN_WATCH (ID varchar(32) PRIMARY KEY, STATUS smallint, DELETED smallint, "
            + "USER_ID varchar(32), DOMAIN_NAME varchar(255), NOTIFY_TYPE smallint, NOTIFY_EMAIL varchar(254)) ENGINE=InnoDB");
        execute("CREATE TABLE WEB_MONITOR_EVENT (ID varchar(32) PRIMARY KEY, WATCH_ID varchar(32), "
            + "EVENT_TYPE varchar(64)) ENGINE=InnoDB");
        execute("CREATE TABLE WEB_NOTIFICATION_PREFERENCE (ID varchar(32) PRIMARY KEY, STATUS smallint, DELETED smallint, "
            + "CREATE_TIME datetime, UPDATE_TIME datetime, USER_ID varchar(32) NOT NULL, EMAIL_MODE varchar(32), "
            + "DOMAIN_EXPIRY_ENABLED tinyint, SSL_EXPIRY_ENABLED tinyint, DOMAIN_STATUS_ENABLED tinyint, "
            + "DNS_CHANGE_ENABLED tinyint, WEBSITE_AVAILABILITY_ENABLED tinyint, UNIQUE KEY UK_PREF_USER (USER_ID)) ENGINE=InnoDB");
        execute("CREATE TABLE WEB_NOTIFICATION_DELIVERY_BATCH (ID varchar(32) PRIMARY KEY, STATUS smallint, DELETED smallint DEFAULT 0, USER_ID varchar(32), EMAIL_MODE varchar(32), RECIPIENT_EMAIL varchar(254), WINDOW_KEY varchar(128), STATE varchar(32), ATTEMPT_COUNT int, CLAIM_TOKEN varchar(64), CLAIM_UNTIL datetime, CANCELLATION_REQUESTED tinyint DEFAULT 0, NEXT_ATTEMPT_AT datetime, COMPLETED_AT datetime, CREATE_TIME datetime, UPDATE_TIME datetime, UNIQUE KEY UK_BATCH_ROUTE (USER_ID,EMAIL_MODE,RECIPIENT_EMAIL,WINDOW_KEY)) ENGINE=InnoDB");
        execute("CREATE TABLE WEB_USER_NOTIFICATION (ID varchar(32) PRIMARY KEY, STATUS smallint, DELETED smallint, "
            + "CREATE_TIME datetime, UPDATE_TIME datetime, USER_ID varchar(32), EVENT_ID varchar(32), TITLE varchar(255), "
            + "TARGET_PATH varchar(500), EMAIL_MODE varchar(32), EMAIL_STATE varchar(32), EMAIL_ATTEMPT_COUNT int, "
            + "EMAIL_CLAIM_TOKEN varchar(64), EMAIL_CLAIM_UNTIL datetime, DELIVERY_BATCH_ID varchar(32), "
            + "RECIPIENT_EMAIL varchar(254), EMAILED_AT datetime) ENGINE=InnoDB");
        execute("CREATE TABLE WEB_DOMAIN_WATCH_NOTIFY_LOG (ID varchar(32) PRIMARY KEY, STATUS smallint, DELETED smallint, CREATE_TIME datetime, UPDATE_TIME datetime, USER_ID varchar(32), WATCH_ID varchar(32), EVENT_ID varchar(32), NOTIFICATION_ID varchar(32), BATCH_ID varchar(32), DOMAIN_NAME varchar(255), NOTIFY_EMAIL varchar(254), TO_EMAIL varchar(254), DELIVERY_MODE varchar(32), SEND_STATUS smallint, RETRY_COUNT int, ATTEMPT_NO int, ERROR_MESSAGE varchar(500), ERROR_MSG varchar(500), CLAIM_TOKEN varchar(64), SENT_AT datetime, DAYS_LEFT int, SUBJECT varchar(255)) ENGINE=InnoDB");
    }
}
