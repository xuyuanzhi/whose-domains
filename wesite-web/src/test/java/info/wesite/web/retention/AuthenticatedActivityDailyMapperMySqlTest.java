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

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class AuthenticatedActivityDailyMapperMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
            .withDatabaseName("whose_domains").withUsername("whose").withPassword("domains");

    private static DataSource dataSource;
    private static AuthenticatedActivityDailyMapper mapper;

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
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        MapperFactoryBean<AuthenticatedActivityDailyMapper> factory =
                new MapperFactoryBean<>(AuthenticatedActivityDailyMapper.class);
        factory.setSqlSessionFactory(sessionFactory);
        factory.afterPropertiesSet();
        mapper = factory.getObject();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("DELETE FROM WEB_AUTHENTICATED_ACTIVITY_DAILY");
    }

    @Test
    void recordsAtMostOneMinimalFactPerUserAndLocalDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 9);

        mapper.recordDaily("user-1", date);
        mapper.recordDaily("user-1", date);
        mapper.recordDaily("user-2", date);

        assertEquals(2, scalar("SELECT COUNT(*) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY "
                + "WHERE USER_ID='user-1' AND ACTIVITY_DATE='2026-08-09'"));
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
