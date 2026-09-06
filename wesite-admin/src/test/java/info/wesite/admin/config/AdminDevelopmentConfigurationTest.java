package info.wesite.admin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

class AdminDevelopmentConfigurationTest {

    @Test
    void developmentProfileProvidesLocalAdminRuntimeConfiguration() throws IOException {
        Properties development = PropertiesLoaderUtils.loadProperties(
            new ClassPathResource("application-dev.properties"));

        assertEquals("8082", development.getProperty("server.port"));
        assertEquals("/", development.getProperty("server.servlet.context-path"));
        assertEquals("com.mysql.cj.jdbc.Driver",
            development.getProperty("spring.datasource.driver-class-name"));
        assertEquals(
            "jdbc:mysql://localhost:3306/cangliu?allowMultiQueries=true&useUnicode=true"
                + "&characterEncoding=UTF-8&useSSL=false&autoreconnect=true&cachePrepStmts=true"
                + "&useServerPrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048"
                + "&allowPublicKeyRetrieval=true",
            development.getProperty("spring.datasource.url"));
        assertEquals("root", development.getProperty("spring.datasource.username"));
        assertEquals("123456", development.getProperty("spring.datasource.password"));
        assertEquals("2", development.getProperty("spring.datasource.hikari.minimum-idle"));
        assertEquals("8", development.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals("C:/Users/Yuz/IdeaProjects/commerce-java/commerce-core/src/main/resources/GeoLite2-City.mmdb",
            development.getProperty("maxmind.city.file.path"));
        assertEquals("C:/Users/Yuz/Downloads/GeoLite2-ASN_20251118.tar/GeoLite2-ASN_20251118/GeoLite2-ASN.mmdb",
            development.getProperty("maxmind.asn.file.path"));
        assertEquals("127.0.0.1", development.getProperty("spring.data.redis.host"));
        assertEquals("6379", development.getProperty("spring.data.redis.port"));
        assertEquals("8", development.getProperty("spring.data.redis.lettuce.pool.max-active"));
        assertEquals("arwtewrgegsdgswer234234234sdfsdf",
            development.getProperty("app.jwt.secret"));
        assertEquals("system", development.getProperty("app.jwt.issuer"));
        Properties effective = PropertiesLoaderUtils.loadProperties(
            new ClassPathResource("application.properties"));
        effective.putAll(development);
        assertEquals("classpath:/views/", effective.getProperty("spring.thymeleaf.prefix"));
        assertEquals("false", effective.getProperty("spring.thymeleaf.cache"));
        assertEquals("WARN", development.getProperty("logging.level.root"));
        assertEquals("INFO", development.getProperty("logging.level.info.wesite"));
    }
}
