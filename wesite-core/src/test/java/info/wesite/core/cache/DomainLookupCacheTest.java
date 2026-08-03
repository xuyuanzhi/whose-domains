package info.wesite.core.cache;

import info.wesite.core.entity.Domain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DomainLookupCacheTest {
    @Test
    void returnsCachedDomainForCaseInsensitiveLookup() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        Domain cached = new Domain();
        cached.setName("example.com");
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("domain:lookup:v1:example.com")).thenReturn("{\"name\":\"example.com\"}");

        Domain result = new DomainLookupCache(redis).get("Example.COM");

        assertNotNull(result);
        assertEquals("example.com", result.getName());
    }
}
