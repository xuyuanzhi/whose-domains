package info.wesite.core.health;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import info.wesite.core.config.AccessControl;

@RestController
@AccessControl(level = AccessControl.Level.NONE)
public class ReadinessController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadinessController.class);
    private static final Map<String, String> UP = Map.of("status", "UP");
    private static final Map<String, String> DOWN = Map.of("status", "DOWN");

    private final JdbcTemplate database;
    private final RedisConnectionFactory redisFactory;

    public ReadinessController(JdbcTemplate database, RedisConnectionFactory redisFactory) {
        this.database = database;
        this.redisFactory = redisFactory;
    }

    @GetMapping(value = "/api/readyz", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> readiness() {
        if (!databaseReady() || !redisReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(DOWN);
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(UP);
    }

    private boolean databaseReady() {
        try {
            Integer result = database.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(result);
        } catch (RuntimeException failure) {
            LOGGER.warn("Readiness check failed for database ({})",
                failure.getClass().getSimpleName());
            return false;
        }
    }

    private boolean redisReady() {
        try (RedisConnection connection = redisFactory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException failure) {
            LOGGER.warn("Readiness check failed for Redis ({})",
                failure.getClass().getSimpleName());
            return false;
        }
    }
}
