package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.mapper.MonitorEventMapper;
import info.wesite.core.mapper.MonitorSnapshotMapper;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.service.impl.MonitorEventServiceImpl;
import info.wesite.core.service.impl.MonitorSnapshotServiceImpl;
import info.wesite.core.service.impl.UserNotificationServiceImpl;
import info.wesite.core.utils.RandomUtils;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class MonitorEventPublisherMySqlConcurrencyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-09T12:34:56Z"), ZoneOffset.UTC);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
        .withDatabaseName("whose_domains")
        .withUsername("whose")
        .withPassword("domains")
        .withCommand("--transaction-isolation=REPEATABLE-READ");

    private static DataSource dataSource;
    private static DataSourceTransactionManager transactionManager;
    private static MonitorSnapshotService snapshotService;
    private static MonitorEventService eventService;
    private static UserNotificationService notificationService;
    private static MonitorEventMapper eventMapper;
    private static UserNotificationMapper notificationMapper;

    @BeforeAll
    static void configureDatabase() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        transactionManager = new DataSourceTransactionManager(dataSource);
        createTables();

        MybatisSqlSessionFactoryBean sessionFactoryBean = new MybatisSqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(dataSource);
        sessionFactoryBean.afterPropertiesSet();
        SqlSessionFactory sessionFactory = sessionFactoryBean.getObject();
        MonitorSnapshotMapper snapshotMapper = mapper(MonitorSnapshotMapper.class, sessionFactory);
        eventMapper = mapper(MonitorEventMapper.class, sessionFactory);
        notificationMapper = mapper(UserNotificationMapper.class, sessionFactory);

        MonitorSnapshotServiceImpl snapshots = new MonitorSnapshotServiceImpl();
        ReflectionTestUtils.setField(snapshots, "baseMapper", snapshotMapper);
        snapshotService = snapshots;
        MonitorEventServiceImpl events = new MonitorEventServiceImpl();
        ReflectionTestUtils.setField(events, "baseMapper", eventMapper);
        eventService = events;
        UserNotificationServiceImpl notifications = new UserNotificationServiceImpl();
        ReflectionTestUtils.setField(notifications, "baseMapper", notificationMapper);
        notificationService = notifications;
    }

    @AfterAll
    static void releaseDatabase() {
        dataSource = null;
        transactionManager = null;
        snapshotService = null;
        eventService = null;
        notificationService = null;
        eventMapper = null;
        notificationMapper = null;
    }

    @BeforeEach
    void resetRows() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM WEB_USER_NOTIFICATION");
            statement.executeUpdate("DELETE FROM WEB_MONITOR_EVENT");
            statement.executeUpdate("DELETE FROM WEB_MONITOR_SNAPSHOT");
        }
    }

    @Test
    void concurrentPublishersConvergeAfterDuplicateKeysUnderRepeatableRead() throws Exception {
        MonitorState previous = state(Set.of("ok"));
        insertBaseline(previous);
        CyclicBarrier insertsReady = new CyclicBarrier(2);
        MonitorEventService synchronizedEvents = barrierBeforeSave(eventService, insertsReady);
        MonitorEventPublisher publisher = new MonitorEventPublisher(
            snapshotService,
            synchronizedEvents,
            notificationService,
            eventMapper,
            notificationMapper,
            new MonitorChangeDetector(CLOCK),
            CLOCK);
        DomainWatch watch = watch();
        MonitorState current = state(Set.of("clientHold"));

        List<Attempt> attempts = concurrently(
            () -> publishInTransaction(publisher, watch, current),
            () -> publishInTransaction(publisher, watch, current));

        assertNull(attempts.get(0).error());
        assertNull(attempts.get(1).error());
        assertEquals(attempts.get(0).events().get(0).getId(), attempts.get(1).events().get(0).getId());
        assertEquals(1, count("WEB_MONITOR_EVENT"));
        assertEquals(1, count("WEB_USER_NOTIFICATION"));
        assertEquals(3, count("WEB_MONITOR_SNAPSHOT"));
    }

    private Attempt publishInTransaction(
        MonitorEventPublisher publisher,
        DomainWatch watch,
        MonitorState current) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            List<MonitorEvent> events = transaction.execute(status -> publisher.publish(watch, current, true));
            return new Attempt(events, null);
        } catch (RuntimeException error) {
            return new Attempt(null, error);
        }
    }

    private static void insertBaseline(MonitorState state) {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        initialize(snapshot);
        snapshot.setWatchId("watch-1");
        snapshot.setCheckedAt(Date.from(CLOCK.instant().minusSeconds(60)));
        snapshot.setStateJson(JSON.toJSONString(state));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> snapshotService.save(snapshot));
    }

    private static MonitorEventService barrierBeforeSave(
        MonitorEventService delegate,
        CyclicBarrier barrier) {
        return (MonitorEventService) Proxy.newProxyInstance(
            MonitorEventService.class.getClassLoader(),
            new Class<?>[] { MonitorEventService.class },
            (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return invoke(delegate, method, arguments);
                }
                if (method.getName().equals("save")) {
                    barrier.await(10, TimeUnit.SECONDS);
                }
                return invoke(delegate, method, arguments);
            });
    }

    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> firstFuture = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return first.call();
            });
            Future<T> secondFuture = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return second.call();
            });
            start.countDown();
            return Arrays.asList(firstFuture.get(30, TimeUnit.SECONDS), secondFuture.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static int count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static DomainWatch watch() {
        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setUserId("user-1");
        watch.setDomainName("example.com");
        return watch;
    }

    private static MonitorState state(Set<String> statuses) {
        return new MonitorState(
            "example.com",
            statuses,
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 1, 1),
            Map.of("A", Set.of("192.0.2.1")),
            true,
            0);
    }

    private static void initialize(info.wesite.core.entity.BaseEntity entity) {
        entity.setId(RandomUtils.generateId());
        entity.setStatus(BaseEntity.STATUS_ACTIVE);
        entity.setDeleted(0);
        entity.setCreateTime(Date.from(CLOCK.instant()));
    }

    private static <T> T mapper(Class<T> type, SqlSessionFactory sessionFactory) throws Exception {
        MapperFactoryBean<T> factory = new MapperFactoryBean<>(type);
        factory.setSqlSessionFactory(sessionFactory);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    private static void createTables() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE WEB_MONITOR_SNAPSHOT (
                  ID varchar(32) NOT NULL PRIMARY KEY,
                  STATUS smallint DEFAULT 1,
                  DELETED smallint DEFAULT 0,
                  CREATE_BY varchar(50), CREATE_TIME datetime,
                  UPDATE_BY varchar(50), UPDATE_TIME datetime,
                  WATCH_ID varchar(32) NOT NULL,
                  CHECKED_AT datetime NOT NULL,
                  STATE_JSON mediumtext NOT NULL,
                  SCHEMA_VERSION smallint NULL,
                  OBSERVED_SOURCES varchar(128) NULL,
                  KEY IDX_MONITOR_SNAPSHOT_WATCH_CHECKED (WATCH_ID, CHECKED_AT)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
                """);
            statement.execute("""
                CREATE TABLE WEB_MONITOR_EVENT (
                  ID varchar(32) NOT NULL PRIMARY KEY,
                  STATUS smallint DEFAULT 1,
                  DELETED smallint DEFAULT 0,
                  CREATE_BY varchar(50), CREATE_TIME datetime,
                  UPDATE_BY varchar(50), UPDATE_TIME datetime,
                  WATCH_ID varchar(32) NOT NULL,
                  SNAPSHOT_ID varchar(32),
                  FINGERPRINT varchar(128) NOT NULL,
                  EVENT_TYPE varchar(64) NOT NULL,
                  RISK varchar(16), SOURCE varchar(32),
                  OLD_VALUE text, NEW_VALUE text,
                  OCCURRED_AT datetime NOT NULL,
                  UNIQUE KEY UK_MONITOR_EVENT_WATCH_FINGERPRINT (WATCH_ID, FINGERPRINT)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
                """);
            statement.execute("""
                CREATE TABLE WEB_USER_NOTIFICATION (
                  ID varchar(32) NOT NULL PRIMARY KEY,
                  STATUS smallint DEFAULT 1,
                  DELETED smallint DEFAULT 0,
                  CREATE_BY varchar(50), CREATE_TIME datetime,
                  UPDATE_BY varchar(50), UPDATE_TIME datetime,
                  USER_ID varchar(32) NOT NULL,
                  EVENT_ID varchar(32) NOT NULL,
                  TITLE varchar(255) NOT NULL,
                  CONTENT text,
                  TARGET_PATH varchar(500) NOT NULL,
                  READ_AT datetime,
                  EMAIL_STATE varchar(32) NOT NULL,
                  EMAILED_AT datetime,
                  UNIQUE KEY UK_USER_NOTIFICATION_USER_EVENT (USER_ID, EVENT_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
                """);
        }
    }

    private record Attempt(List<MonitorEvent> events, Throwable error) {
    }
}
