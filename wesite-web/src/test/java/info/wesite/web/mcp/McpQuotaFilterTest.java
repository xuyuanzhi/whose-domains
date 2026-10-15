package info.wesite.web.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpQuotaFilterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private McpProperties properties;
    private McpQuotaFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        properties = new McpProperties();
        filter = new McpQuotaFilter(redis, properties);
    }

    private MockHttpServletRequest request(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void perIpRateLimitReturns429() throws Exception {
        properties.setRequestsPerMinute(2);
        when(values.increment(anyString())).thenReturn(1L);

        int status = 0;
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("203.0.113.90"), response, new MockFilterChain());
            status = response.getStatus();
        }
        assertEquals(429, status);
    }

    @Test
    void dailyQuotaExceededReturns429() throws Exception {
        properties.setRequestsPerMinute(100);
        properties.setDailyRequestLimit(1);
        when(values.increment(anyString())).thenReturn(2L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.91"), response, new MockFilterChain());

        assertEquals(429, response.getStatus());
    }

    @Test
    void redisFailureFailsOpen() throws Exception {
        properties.setRequestsPerMinute(100);
        when(values.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("203.0.113.92"), response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }
}
