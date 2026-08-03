package info.wesite.web.schema;

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
        assertTrue(sql.contains("GOOGLE_SUB"));
        assertTrue(sql.contains("UNIQUE KEY `IDX_USER_GOOGLE_SUB`"));
    }
}
