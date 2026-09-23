package info.wesite.web.controller.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import info.wesite.core.entity.Domain;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;

class RdapLookupConsistencyTest {
    private ResponseJson<?> lookup(String raw) {
        Domain stored = new Domain();
        stored.setName("example.com");
        stored.setStatus(Domain.STATUS_ACTIVE);
        stored.setRegistExpiryDateText("2027-08-13T04:00:00Z");
        stored.setNameServers("new.example.net");
        stored.setRegistrantEmail("unrelated@example.net");
        stored.setRdapText(raw);
        DomainService service = mock(DomainService.class);
        when(service.getOne(any())).thenReturn(stored);
        ViewController controller = new ViewController();
        ReflectionTestUtils.setField(controller, "domainService", service);
        try (var domains = mockStatic(DomainUtils.class); var limits = mockStatic(RateLimitUtils.class)) {
            domains.when(() -> DomainUtils.getMainDomain("example.com")).thenReturn("example.com");
            domains.when(() -> DomainUtils.getDomainInfoByMainName("example.com")).thenReturn(stored);
            limits.when(() -> RateLimitUtils.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);
            var request = new ViewController.WhoisLookupRequest();
            request.setDomain("example.com");
            return controller.rdapLookupSubmit(request, new MockHttpServletRequest());
        }
    }

    @Test
    void summaryUsesTheSameSnapshotAsRawJsonWithoutMergedWhoisFields() {
        var response = lookup("""
            {"objectClassName":"domain","ldhName":"EXAMPLE.COM","handle":"snapshot-id",
             "events":[{"eventAction":"expiration","eventDate":"2026-08-13T04:00:00Z"},
                       {"eventAction":"last changed","eventDate":"2025-08-14T07:01:39Z"},
                       {"eventAction":"last update of RDAP database","eventDate":"2025-10-03T12:23:01Z"}],
             "nameservers":[{"ldhName":"old.example.net"}],"entities":[]}
            """);
        assertEquals(0, response.getCode());
        Map<?,?> data = (Map<?,?>) response.getData();
        assertEquals("2026-08-13T04:00:00Z", data.get("expiresAt"));
        assertEquals("2025-08-14T07:01:39Z", data.get("updatedAt"));
        assertEquals(List.of("old.example.net"), data.get("nameservers"));
        assertNull(((Map<?,?>)data.get("registrant")).get("email"));
    }

    @Test
    void sparseRdapDoesNotBorrowUnrelatedStoredFields() {
        var response = lookup("{\"objectClassName\":\"domain\",\"ldhName\":\"example.com\"}");
        assertEquals(0, response.getCode());
        Map<?,?> data = (Map<?,?>) response.getData();
        assertNull(data.get("expiresAt"));
        assertEquals(List.of(), data.get("nameservers"));
    }

    @Test
    void missingRdapIsNotPresentedAsAnRdapResult() {
        assertNotEquals(0, lookup(null).getCode());
    }

    @Test
    void differentDomainInRawResponseIsRejected() {
        assertNotEquals(0, lookup("{\"objectClassName\":\"domain\",\"ldhName\":\"other.com\"}").getCode());
    }
}
