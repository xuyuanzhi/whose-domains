package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
class BlogEditorialMigrationMySqlTest {

    private static final Path CREATE_SCHEMA = Path.of("..", "doc", "create.sql");
    private static final Path MIGRATION = Path.of("..", "doc", "alter_blog_editorial_workflow.sql");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
        .withDatabaseName("whose_domains")
        .withUsername("whose")
        .withPassword("domains");

    @BeforeEach
    void dropBlogTable() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS WEB_BLOG_POST");
        }
    }

    @Test
    void freshInstallSchemaCreatesEditorialTimestampColumn() throws Exception {
        String schema = Files.readString(CREATE_SCHEMA, StandardCharsets.UTF_8);
        String createBlogTable = extractStatement(schema, "CREATE TABLE `WEB_BLOG_POST`");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(createBlogTable);
            assertTrue(columnExists(connection, "CONTENT_UPDATED_AT"));
        }
    }

    @Test
    void migrationBackfillsOnlyPublishedRowsAndCanRunTwice() throws Exception {
        Timestamp publishedAt = Timestamp.valueOf("2026-08-31 10:20:30");
        Timestamp preservedEditorialTime = Timestamp.valueOf("2026-09-01 11:22:33");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE WEB_BLOG_POST (
                    ID VARCHAR(32) PRIMARY KEY,
                    STATUS TINYINT NOT NULL,
                    DELETED TINYINT NOT NULL,
                    PUBLISH_DATE DATETIME NULL
                ) CHARACTER SET utf8mb4
                """);
            insert(connection, "published", 1, 0, publishedAt);
            insert(connection, "draft", 0, 0, publishedAt);
            insert(connection, "deleted", 1, 1, publishedAt);

            executeMigration(connection);

            assertEquals(publishedAt, readEditorialTime(connection, "published"));
            assertNull(readEditorialTime(connection, "draft"));
            assertNull(readEditorialTime(connection, "deleted"));

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE WEB_BLOG_POST SET CONTENT_UPDATED_AT = ? WHERE ID = 'published'")) {
                update.setTimestamp(1, preservedEditorialTime);
                update.executeUpdate();
            }

            executeMigration(connection);

            assertEquals(preservedEditorialTime, readEditorialTime(connection, "published"));
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            MYSQL.getJdbcUrl() + "?allowMultiQueries=true",
            MYSQL.getUsername(),
            MYSQL.getPassword());
    }

    private static void executeMigration(Connection connection) throws Exception {
        assertTrue(Files.exists(MIGRATION), "editorial migration script must exist");
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String extractStatement(String script, String prefix) {
        int start = script.indexOf(prefix);
        assertTrue(start >= 0, "missing statement: " + prefix);
        int end = script.indexOf(';', start);
        assertTrue(end > start, "unterminated statement: " + prefix);
        return script.substring(start, end + 1);
    }

    private static boolean columnExists(Connection connection, String columnName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'WEB_BLOG_POST'
                  AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1) == 1;
            }
        }
    }

    private static void insert(
            Connection connection,
            String id,
            int status,
            int deleted,
            Timestamp publishDate) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO WEB_BLOG_POST (ID, STATUS, DELETED, PUBLISH_DATE) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setInt(2, status);
            statement.setInt(3, deleted);
            statement.setTimestamp(4, publishDate);
            statement.executeUpdate();
        }
    }

    private static Timestamp readEditorialTime(Connection connection, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT CONTENT_UPDATED_AT FROM WEB_BLOG_POST WHERE ID = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getTimestamp(1);
            }
        }
    }
}
