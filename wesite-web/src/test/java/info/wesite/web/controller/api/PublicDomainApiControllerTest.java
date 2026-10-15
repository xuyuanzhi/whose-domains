package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import info.wesite.core.entity.ApiKey;
import info.wesite.core.entity.ApiPlan;
import info.wesite.core.entity.Domain;
import info.wesite.core.model.QuotaDecision;
import info.wesite.core.service.ApiKeyService;
import info.wesite.core.service.ApiPlanService;
import info.wesite.core.service.ApiQuotaService;
import info.wesite.core.service.DomainService;

class PublicDomainApiControllerTest {

    private ApiPlan freePlan() {
        ApiPlan plan = new ApiPlan();
        plan.setCode("free");
        plan.setDailyRequestLimit(100);
        plan.setMonthlyRequestLimit(3000);
        return plan;
    }

    private PublicDomainApiController controller(ApiKeyService keys, DomainService domains,
            ApiQuotaService quota) {
        ApiPlanService plans = mock(ApiPlanService.class);
        when(plans.resolveForUser(any())).thenReturn(freePlan());
        return new PublicDomainApiController(keys, domains, plans, quota);
    }

    @Test
    void missingKeyIsRejectedBeforeAnyLookup() {
        PublicDomainApiController controller = controller(
                mock(ApiKeyService.class), mock(DomainService.class), mock(ApiQuotaService.class));

        ResponseEntity<?> response = controller.domain("example.com", null, new MockHttpServletRequest());

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void malformedDomainIsRejected() {
        ApiKeyService keys = mock(ApiKeyService.class);
        ApiKey apiKey = new ApiKey();
        apiKey.setUserId("user-1");
        when(keys.getOne(any())).thenReturn(apiKey);
        PublicDomainApiController controller = controller(keys, mock(DomainService.class), mock(ApiQuotaService.class));

        ResponseEntity<?> response = controller.domain("not-a-domain", "wd_test", new MockHttpServletRequest());

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulPublicResponseExcludesRegistrantAndTechnicalContactPii() {
        ApiKeyService keys = mock(ApiKeyService.class);
        DomainService domains = mock(DomainService.class);
        ApiQuotaService quota = mock(ApiQuotaService.class);
        ApiKey apiKey = new ApiKey();
        apiKey.setUserId("user-2");
        Domain domain = new Domain();
        domain.setName("example.com");
        domain.setRegistrar("Example Registrar");
        domain.setRegistrantEmail("private@example.com");
        domain.setRegistrantPhone("+1-555-0100");
        domain.setTechEmail("tech@example.com");
        when(keys.getOne(any())).thenReturn(apiKey);
        when(domains.getOne(any())).thenReturn(domain);
        when(quota.consume(any(), any(), any(), anyInt())).thenReturn(QuotaDecision.allowed(1, 100, 1, 3000));
        PublicDomainApiController controller = controller(keys, domains, quota);

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
    void quotaExhaustionReturnsTooManyRequests() {
        ApiKeyService keys = mock(ApiKeyService.class);
        ApiQuotaService quota = mock(ApiQuotaService.class);
        ApiKey apiKey = new ApiKey();
        apiKey.setUserId("user-3");
        when(keys.getOne(any())).thenReturn(apiKey);
        when(quota.consume(any(), any(), any(), anyInt()))
                .thenReturn(QuotaDecision.denied("daily", 100, 100, 0, 3000));
        PublicDomainApiController controller = controller(keys, mock(DomainService.class), quota);

        ResponseEntity<?> response = controller.domain("example.com", "wd_test", new MockHttpServletRequest());

        assertEquals(429, response.getStatusCode().value());
    }

    @Test
    void meteringFailureReturnsServiceUnavailable() {
        ApiKeyService keys = mock(ApiKeyService.class);
        ApiQuotaService quota = mock(ApiQuotaService.class);
        ApiKey apiKey = new ApiKey();
        apiKey.setUserId("user-4");
        when(keys.getOne(any())).thenReturn(apiKey);
        when(quota.consume(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("redis unavailable"));
        PublicDomainApiController controller = controller(keys, mock(DomainService.class), quota);

        ResponseEntity<?> response = controller.domain("example.com", "wd_test", new MockHttpServletRequest());

        assertEquals(503, response.getStatusCode().value());
    }
}
