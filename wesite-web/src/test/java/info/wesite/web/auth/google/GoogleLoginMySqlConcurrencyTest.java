package info.wesite.web.auth.google;

import static info.wesite.web.auth.google.GoogleLoginException.Code.ACCOUNT_CONFLICT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.User;
import info.wesite.core.mapper.UserMapper;
import info.wesite.core.service.UserService;
import info.wesite.core.service.impl.UserServiceImpl;
import info.wesite.web.auth.EmailLoginService;
import info.wesite.web.auth.EmailLoginCompletionService;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class GoogleLoginMySqlConcurrencyTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
            .withDatabaseName("whose_domains")
            .withUsername("whose")
            .withPassword("domains")
            .withCommand("--transaction-isolation=REPEATABLE-READ");

    private static DataSource dataSource;
    private static DataSourceTransactionManager transactionManager;
    private static UserMapper userMapper;
    private static UserService realUserService;

    @BeforeAll
    static void configureDatabase() throws Exception {
        DriverManagerDataSource configuredDataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        dataSource = configuredDataSource;
        transactionManager = new DataSourceTransactionManager(dataSource);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE SYS_USER (
                      ID varchar(32) NOT NULL,
                      STATUS smallint DEFAULT 1,
                      DELETED smallint DEFAULT 0,
                      CREATE_BY varchar(50),
                      CREATE_TIME datetime,
                      UPDATE_BY varchar(50),
                      UPDATE_TIME datetime,
                      NAME varchar(100),
                      PHONE_NO varchar(50),
                      EMAIL varchar(255) COLLATE utf8mb4_bin,
                      GOOGLE_SUB varchar(255) COLLATE utf8mb4_bin,
                      PASSWORD varchar(200),
                      SECURE_KEY varchar(200),
                      VCODE varchar(50),
                      VCODE_TIME datetime,
                      USER_TYPE varchar(20),
                      PRIMARY KEY (ID),
                      UNIQUE KEY IDX_USER_EMAIL (EMAIL),
                      UNIQUE KEY IDX_USER_GOOGLE_SUB (GOOGLE_SUB)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
                    """);
        }

        MybatisSqlSessionFactoryBean sessionFactoryBean = new MybatisSqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(dataSource);
        sessionFactoryBean.afterPropertiesSet();
        SqlSessionFactory sessionFactory = sessionFactoryBean.getObject();
        MapperFactoryBean<UserMapper> mapperFactory = new MapperFactoryBean<>(UserMapper.class);
        mapperFactory.setSqlSessionFactory(sessionFactory);
        mapperFactory.afterPropertiesSet();
        userMapper = mapperFactory.getObject();

        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        realUserService = service;
    }

    @AfterAll
    static void releaseDatabase() {
        dataSource = null;
        transactionManager = null;
        userMapper = null;
        realUserService = null;
    }

    @BeforeEach
    void clearUsers() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM SYS_USER");
        }
    }

    @Test
    void databaseKeepsAccentedNormalizedEmailsAsDistinctIdentities() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO SYS_USER (ID, EMAIL) VALUES ('accented', 'caf\u00e9@example.com')");
            statement.executeUpdate("INSERT INTO SYS_USER (ID, EMAIL) VALUES ('plain', 'cafe@example.com')");
        }

        assertEquals(2, userCount());
    }

    @Test
    void concurrentSameSubjectBindingIsIdempotentAcrossTwoRealTransactions() throws Exception {
        insert(activeUser("existing", "person@gmail.com", null));
        CyclicBarrier updatesReady = new CyclicBarrier(2);
        GoogleLoginService service = service(barrierBefore(realUserService, "update", updatesReady));

        List<Attempt> attempts = concurrently(
                () -> authenticate(service, identity("same-subject")),
                () -> authenticate(service, identity("same-subject")));

        assertNull(attempts.get(0).error());
        assertNull(attempts.get(1).error());
        assertEquals("existing", attempts.get(0).result().user().getId());
        assertEquals("existing", attempts.get(1).result().user().getId());
        assertEquals("same-subject", selectById("existing").getGoogleSub());
        assertEquals(1, userCount());
    }

    @Test
    void concurrentDifferentSubjectBindingReturnsOneConflictAcrossTwoRealTransactions() throws Exception {
        insert(activeUser("existing", "person@gmail.com", null));
        CyclicBarrier updatesReady = new CyclicBarrier(2);
        GoogleLoginService service = service(barrierBefore(realUserService, "update", updatesReady));

        List<Attempt> attempts = concurrently(
                () -> authenticate(service, identity("subject-a")),
                () -> authenticate(service, identity("subject-b")));

        List<Attempt> successes = attempts.stream().filter(attempt -> attempt.error() == null).toList();
        List<Attempt> failures = attempts.stream().filter(attempt -> attempt.error() != null).toList();
        assertEquals(1, successes.size());
        assertEquals(1, failures.size());
        GoogleLoginException conflict = assertInstanceOf(GoogleLoginException.class, failures.get(0).error());
        assertEquals(ACCOUNT_CONFLICT, conflict.code());
        assertEquals(successes.get(0).result().user().getGoogleSub(), selectById("existing").getGoogleSub());
        assertEquals(1, userCount());
    }

    @Test
    void concurrentSameSubjectBindingToDifferentUsersReturnsOneConflictAcrossTwoRealTransactions() throws Exception {
        insert(activeUser("user-a", "a@gmail.com", null));
        insert(activeUser("user-b", "b@gmail.com", null));
        CyclicBarrier updatesReady = new CyclicBarrier(2);
        GoogleLoginService service = service(barrierBefore(realUserService, "update", updatesReady));

        List<Attempt> attempts = concurrently(
                () -> authenticate(service, identity("same-subject", "a@gmail.com")),
                () -> authenticate(service, identity("same-subject", "b@gmail.com")));

        List<Attempt> successes = attempts.stream().filter(attempt -> attempt.error() == null).toList();
        List<Attempt> failures = attempts.stream().filter(attempt -> attempt.error() != null).toList();
        assertEquals(1, successes.size());
        assertEquals(1, failures.size());
        GoogleLoginException conflict = assertInstanceOf(GoogleLoginException.class, failures.get(0).error());
        assertEquals(ACCOUNT_CONFLICT, conflict.code());
        assertEquals("same-subject", successes.get(0).result().user().getGoogleSub());
        assertEquals(1, googleSubjectCount("same-subject"));
        assertEquals(2, userCount());
    }

    @Test
    void concurrentDuplicateEmailCreateConvergesOnOneUserAcrossTwoRealTransactions() throws Exception {
        CyclicBarrier savesReady = new CyclicBarrier(2);
        GoogleLoginService service = service(barrierBefore(realUserService, "save", savesReady));

        List<Attempt> attempts = concurrently(
                () -> authenticate(service, identity("same-subject")),
                () -> authenticate(service, identity("same-subject")));

        assertNull(attempts.get(0).error());
        assertNull(attempts.get(1).error());
        assertEquals(1, userCount());
        User persisted = selectByEmail("person@gmail.com");
        assertNotNull(persisted);
        assertEquals("same-subject", persisted.getGoogleSub());
        assertEquals(persisted.getId(), attempts.get(0).result().user().getId());
        assertEquals(persisted.getId(), attempts.get(1).result().user().getId());
    }

    @Test
    void failedCreateRollsBackBeforeTheOtherRealTransactionConverges() throws Exception {
        CountDownLatch failedInsertCompleted = new CountDownLatch(1);
        UserService failing = failAfterSave(realUserService, failedInsertCompleted);
        UserService waiting = waitBeforeSave(realUserService, failedInsertCompleted);

        List<Attempt> attempts = concurrently(
                () -> authenticate(service(failing), identity("same-subject")),
                () -> authenticate(service(waiting), identity("same-subject")));

        DataAccessResourceFailureException failure = assertInstanceOf(DataAccessResourceFailureException.class,
                attempts.get(0).error());
        assertEquals("forced rollback", failure.getMessage());
        assertNull(attempts.get(1).error());
        assertEquals(1, userCount());
        User persisted = selectByEmail("person@gmail.com");
        assertNotNull(persisted);
        assertEquals(attempts.get(1).result().user().getId(), persisted.getId());
    }

    @Test
    void concurrentOrdinaryEmailCompletionsConvergeOnOneUserAcrossTwoRealTransactions() throws Exception {
        CyclicBarrier savesReady = new CyclicBarrier(2);
        UserService users = barrierBefore(realUserService, "save", savesReady);
        EmailLoginCompletionService completion = completionService(users);

        List<CompletionAttempt> attempts = concurrently(
                () -> completeEmail(completion, null),
                () -> completeEmail(completion, null));

        assertNull(attempts.get(0).error());
        assertNull(attempts.get(1).error());
        assertEquals(attempts.get(0).user().getId(), attempts.get(1).user().getId());
        assertEquals(1, userCount());
    }

    @Test
    void concurrentGoogleEmailCompletionsConvergeOnOneBoundUserAcrossTwoRealTransactions() throws Exception {
        CyclicBarrier savesReady = new CyclicBarrier(2);
        UserService users = barrierBefore(realUserService, "save", savesReady);
        EmailLoginCompletionService completion = completionService(users);
        PendingGoogleBinding pending = new PendingGoogleBinding(null, "same-subject", "person@gmail.com",
                java.time.Instant.now().plusSeconds(300));

        List<CompletionAttempt> attempts = concurrently(
                () -> completeEmail(completion, pending),
                () -> completeEmail(completion, pending));

        assertNull(attempts.get(0).error());
        assertNull(attempts.get(1).error());
        assertEquals(attempts.get(0).user().getId(), attempts.get(1).user().getId());
        User persisted = selectByEmail("person@gmail.com");
        assertNotNull(persisted);
        assertEquals("same-subject", persisted.getGoogleSub());
        assertEquals(1, userCount());
    }

    private GoogleLoginService service(UserService users) {
        return new GoogleLoginService(users, userMapper, mock(EmailLoginService.class));
    }

    private EmailLoginCompletionService completionService(UserService users) {
        return new EmailLoginCompletionService(users, userMapper, service(users));
    }

    private Attempt authenticate(GoogleLoginService service, GoogleIdentity identity) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            GoogleLoginResult result = transaction.execute(status -> service.authenticate(identity, null));
            return new Attempt(result, null);
        } catch (RuntimeException error) {
            return new Attempt(null, error);
        }
    }

    private CompletionAttempt completeEmail(EmailLoginCompletionService service, PendingGoogleBinding pending) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            User user = transaction.execute(status -> service.complete("person@gmail.com", pending));
            return new CompletionAttempt(user, null);
        } catch (RuntimeException error) {
            return new CompletionAttempt(null, error);
        }
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
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

    private UserService barrierBefore(UserService delegate, String methodName, CyclicBarrier barrier) {
        return proxy(delegate, (method, arguments) -> {
            if (method.getName().equals(methodName)) {
                barrier.await(10, TimeUnit.SECONDS);
            }
            return invoke(delegate, method, arguments);
        });
    }

    private UserService failAfterSave(UserService delegate, CountDownLatch insertCompleted) {
        return proxy(delegate, (method, arguments) -> {
            Object result = invoke(delegate, method, arguments);
            if (method.getName().equals("save")) {
                insertCompleted.countDown();
                throw new DataAccessResourceFailureException("forced rollback");
            }
            return result;
        });
    }

    private UserService waitBeforeSave(UserService delegate, CountDownLatch insertCompleted) {
        return proxy(delegate, (method, arguments) -> {
            if (method.getName().equals("save") && !insertCompleted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("failing transaction never inserted");
            }
            return invoke(delegate, method, arguments);
        });
    }

    private UserService proxy(UserService delegate, Invocation invocation) {
        return (UserService) Proxy.newProxyInstance(UserService.class.getClassLoader(),
                new Class<?>[] { UserService.class }, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return invoke(delegate, method, arguments);
                    }
                    return invocation.call(method, arguments);
                });
    }

    private Object invoke(UserService delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private void insert(User user) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> realUserService.save(user));
    }

    private User selectById(String id) throws SQLException {
        return selectOne("SELECT ID, EMAIL, GOOGLE_SUB, STATUS FROM SYS_USER WHERE ID = '" + id + "'");
    }

    private User selectByEmail(String email) throws SQLException {
        return selectOne("SELECT ID, EMAIL, GOOGLE_SUB, STATUS FROM SYS_USER WHERE EMAIL = '" + email + "'");
    }

    private User selectOne(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return null;
            }
            return activeUser(result.getString("ID"), result.getString("EMAIL"), result.getString("GOOGLE_SUB"));
        }
    }

    private int userCount() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM SYS_USER")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int googleSubjectCount(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM SYS_USER WHERE GOOGLE_SUB = '" + subject + "'")) {
            result.next();
            return result.getInt(1);
        }
    }

    private GoogleIdentity identity(String subject) {
        return identity(subject, "person@gmail.com");
    }

    private GoogleIdentity identity(String subject, String email) {
        return new GoogleIdentity(subject, email, "Person", true);
    }

    private User activeUser(String id, String email, String subject) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setGoogleSub(subject);
        user.setStatus(BaseEntity.STATUS_ACTIVE);
        user.setDeleted(0);
        return user;
    }

    private record Attempt(GoogleLoginResult result, Throwable error) {
    }

    private record CompletionAttempt(User user, Throwable error) {
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(Method method, Object[] arguments) throws Throwable;
    }
}
