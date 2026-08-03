package info.wesite.web.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class GoogleLoginSchemaTest {

    @Test
    void migrationNormalizesEmailAndAddsUniqueGoogleSubject() throws IOException {
        String sql = Files.readString(Path.of("..", "doc", "alter_google_login.sql"));
        assertTrue(sql.contains("LOWER(TRIM(`EMAIL`))"));
        assertTrue(sql.contains("COLLATE utf8mb4_bin"));
        assertTrue(sql.contains("SIGNAL SQLSTATE '45000'"));
        assertFalse(sql.contains("utf8mb4_unicode_ci"));
        assertTrue(sql.contains("GOOGLE_SUB"));
        assertTrue(sql.contains("UNIQUE KEY `IDX_USER_GOOGLE_SUB`"));
    }

    @Test
    void freshSchemaKeepsAccentedEmailsDistinctAfterApplicationNormalization() throws IOException {
        String sql = Files.readString(Path.of("..", "doc", "create.sql"));
        int userTableStart = sql.indexOf("CREATE TABLE `SYS_USER`");
        int userTableEnd = sql.indexOf("CREATE TABLE `WEB_EMAIL_LOGIN_LINK`");
        String userTable = sql.substring(userTableStart, userTableEnd);

        assertTrue(userTable.contains("`EMAIL`       varchar(255) COLLATE utf8mb4_bin NULL"));
        assertTrue(userTable.contains("UNIQUE KEY `IDX_USER_EMAIL` (`EMAIL`)"));
        assertFalse(userTable.contains("utf8mb4_unicode_ci"));
    }
}
