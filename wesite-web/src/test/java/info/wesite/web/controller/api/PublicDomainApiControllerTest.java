package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.SetOperations;

import info.wesite.core.service.ApiKeyService;
import info.wesite.core.service.DomainService;
import info.wesite.core.entity.ApiKey;
import info.wesite.core.entity.Domain;

class PublicDomainApiControllerTest {
    @Test
    void missingKeyIsRejectedBeforeAnyLookup() {
        PublicDomainApiController controller = new PublicDomainApiController(
                mock(ApiKeyService.class), mock(DomainService.class), mock(StringRedisTemplate.class));

        ResponseEntity<?> response = controller.domain("example.com", null, new MockHttpServletRequest());

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void malformedDomainIsRejected() {
        ApiKeyService keys = mock(ApiKeyService.class);
        ApiKey apiKey = new ApiKey();
        apiKey.setUserId("user-1");
        when(keys.getOne(any())).thenReturn(apiKey);
        PublicDomainApiController controller = new PublicDomainApiController(keys, mock(DomainService.class), mock(StringRedisTemplate.class));

        ResponseEntity<?> response = controller.domain("not-a-domain", "wd_test", new MockHttpServletRequest());

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulPublicResponseExcludesRegistrantAndTechnicalContactPii() {
        ApiKeyService keys = mock(ApiKeyService.class);
        DomainService domains = mock(DomainService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        ApiKey apiKey = new ApiKey(); apiKey.setUserId("user-2");
        Domain domain = new Domain(); domain.setName("example.com"); domain.setRegistrar("Example Registrar");
        domain.setRegistrantEmail("private@example.com"); domain.setRegistrantPhone("+1-555-0100"); domain.setTechEmail("tech@example.com");
        when(keys.getOne(any())).thenReturn(apiKey);
        when(domains.getOne(any())).thenReturn(domain);
        when(redis.opsForValue()).thenReturn(values); when(redis.opsForSet()).thenReturn(sets);
        when(values.increment(any())).thenReturn(1L);
        PublicDomainApiController controller = new PublicDomainApiController(keys, domains, redis);

        ResponseEntity<?> response = controller.domain("example.com", "wd_test", new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals("Example Registrar", data.get("registrar"));
        org.junit.jupiter.api.Assertions.assertFalse(data.containsKey("registrantEmail"));
        org.junit.jupiter.api.Assertions.assertFalse(data.containsKey("registrantPhone"));
        org.junit.jupiter.api.Assertions.assertFalse(data.containsKey("techEmail"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotaExhaustionReturnsTooManyRequests() {
        ApiKeyService keys = mock(ApiKeyService.class); StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class); SetOperations<String, String> sets = mock(SetOperations.class);
        ApiKey apiKey = new ApiKey(); apiKey.setUserId("user-3");
        when(keys.getOne(any())).thenReturn(apiKey); when(redis.opsForValue()).thenReturn(values); when(redis.opsForSet()).thenReturn(sets);
        when(values.increment(any())).thenReturn(101L);
        ResponseEntity<?> response = new PublicDomainApiController(keys, mock(DomainService.class), redis)
                .domain("example.com", "wd_test", new MockHttpServletRequest());
        assertEquals(429, response.getStatusCode().value());
    }

    @Test
    void meteringFailureReturnsServiceUnavailable() {
        ApiKeyService keys = mock(ApiKeyService.class); StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ApiKey apiKey = new ApiKey(); apiKey.setUserId("user-4"); when(keys.getOne(any())).thenReturn(apiKey);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));
        ResponseEntity<?> response = new PublicDomainApiController(keys, mock(DomainService.class), redis)
                .domain("example.com", "wd_test", new MockHttpServletRequest());
        assertEquals(503, response.getStatusCode().value());
    }
}
