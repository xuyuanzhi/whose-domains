package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class BlogCanonicalLinkMigrationMySqlTest {

    private static final Path MIGRATION = Path.of("..", "doc", "alter_blog_canonical_tool_links.sql");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
        .withDatabaseName("whose_domains")
        .withUsername("whose")
        .withPassword("domains");

    @BeforeEach
    void resetTable() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS WEB_BLOG_POST");
            statement.executeUpdate("""
                CREATE TABLE WEB_BLOG_POST (
                    ID VARCHAR(32) PRIMARY KEY,
                    CONTENT LONGTEXT NOT NULL,
                    UPDATE_TIME DATETIME NULL,
                    PUBLISH_DATE DATETIME NULL
                ) CHARACTER SET utf8mb4
                """);
        }
    }

    @Test
    void rewritesEveryLegacyToolLinkWithoutChangingTimestampsAndIsIdempotent() throws Exception {
        Timestamp updateTime = Timestamp.valueOf("2026-08-01 10:20:30");
        Timestamp publishDate = Timestamp.valueOf("2026-07-31 09:08:07");
        String affected = """
            <a href=\"/tools/domain_analyzer?domain=example.com\">domain</a>
            <a href=\"/tools/dns_analyzer#records\">dns</a>
            <a href=\"/tools/ssl_checker\">ssl</a>
            <a href=\"/tools/competitor_analysis\">competitor</a>
            """;
        String unaffected = "<a href=\"/tools/domain-analyzer\">already canonical</a>";

        try (Connection connection = connection()) {
            insert(connection, "affected", affected, updateTime, publishDate);
            insert(connection, "unaffected", unaffected, updateTime, publishDate);

            String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(sql));
            }

            Row migrated = read(connection, "affected");
            assertTrue(migrated.content().contains("/tools/domain-analyzer?domain=example.com"));
            assertTrue(migrated.content().contains("/tools/dns-analyzer#records"));
            assertTrue(migrated.content().contains("/tools/ssl-checker"));
            assertTrue(migrated.content().contains("/tools/competitor-analysis"));
            assertFalse(CanonicalToolRoutes.containsLegacyInternalLink(migrated.content()));
            assertEquals(updateTime, migrated.updateTime());
            assertEquals(publishDate, migrated.publishDate());
            assertEquals(unaffected, read(connection, "unaffected").content());

            try (Statement statement = connection.createStatement()) {
                assertEquals(0, statement.executeUpdate(sql));
            }
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void insert(
        Connection connection,
        String id,
        String content,
        Timestamp updateTime,
        Timestamp publishDate) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO WEB_BLOG_POST (ID, CONTENT, UPDATE_TIME, PUBLISH_DATE) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, content);
            statement.setTimestamp(3, updateTime);
            statement.setTimestamp(4, publishDate);
            statement.executeUpdate();
        }
    }

    private static Row read(Connection connection, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT CONTENT, UPDATE_TIME, PUBLISH_DATE FROM WEB_BLOG_POST WHERE ID = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return new Row(result.getString(1), result.getTimestamp(2), result.getTimestamp(3));
            }
        }
    }

    private record Row(String content, Timestamp updateTime, Timestamp publishDate) {
    }
}
