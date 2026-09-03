package info.wesite.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class ReadinessControllerTest {

    private JdbcTemplate database;
    private RedisConnectionFactory redisFactory;
    private RedisConnection redis;
    private ReadinessController controller;

    @BeforeEach
    void setUp() {
        database = mock(JdbcTemplate.class);
        redisFactory = mock(RedisConnectionFactory.class);
        redis = mock(RedisConnection.class);
        controller = new ReadinessController(database, redisFactory);
    }

    @Test
    void reportsReadyOnlyWhenDatabaseAndRedisRespond() {
        when(database.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redisFactory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenReturn("PONG");

        ResponseEntity<Map<String, String>> response = controller.readiness();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("status", "UP"), response.getBody());
        verify(redis).close();
    }

    @Test
    void reportsUnavailableWhenDatabaseCannotBeReached() {
        when(database.queryForObject("SELECT 1", Integer.class))
            .thenThrow(new DataAccessResourceFailureException("database credentials leaked here"));

        ResponseEntity<Map<String, String>> response = controller.readiness();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(Map.of("status", "DOWN"), response.getBody());
    }

    @Test
    void reportsUnavailableWhenRedisDoesNotPong() {
        when(database.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redisFactory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenReturn(null);

        ResponseEntity<Map<String, String>> response = controller.readiness();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(Map.of("status", "DOWN"), response.getBody());
        verify(redis).close();
    }
}
