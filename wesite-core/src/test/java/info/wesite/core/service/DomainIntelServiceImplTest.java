package info.wesite.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.model.DomainIntelResult;
import info.wesite.core.model.RegistrationStatus;
import info.wesite.core.service.impl.DomainIntelServiceImpl;
import info.wesite.core.utils.RdapUtils;

class DomainIntelServiceImplTest {

    private DomainService domainService;
    private DomainTldService domainTldService;
    private DomainProbe probe;
    private DomainIntelServiceImpl service;

    @BeforeEach
    void setUp() {
        domainService = mock(DomainService.class);
        domainTldService = mock(DomainTldService.class);
        probe = mock(DomainProbe.class);
        service = new DomainIntelServiceImpl(domainService, domainTldService, probe);

        DomainTld tld = new DomainTld();
        tld.setDisplayName(".com");
        tld.setRdapServer("https://rdap.example/");
        tld.setWhoisServer("whois.example");
        when(domainTldService.getOne(any())).thenReturn(tld);
        when(domainService.getOne(any())).thenReturn(null);
        when(probe.dnsEnabled(anyString())).thenReturn(false);
    }

    @Test
    void invalidInputIsUnknownAndNeverThrows() {
        DomainIntelResult result = service.check("not a domain");
        assertNotNull(result);
        assertEquals(RegistrationStatus.UNKNOWN, result.getStatus());
        assertEquals("none", result.getSource());
        assertTrue(result.getErrors().contains("invalid domain name"));
    }

    @Test
    void databaseRowIsAuthoritativeAndCached() {
        Domain stored = new Domain();
        stored.setName("example.com");
        stored.setStatus(Domain.STATUS_ACTIVE);
        stored.setRegistrar("Example Registrar");
        stored.setRegistExpiryDateText("2030-01-01");
        stored.setDomainStatus("clientTransferProhibited");
        stored.setNameServers("ns1.example.com,ns2.example.com");
        when(domainService.getOne(any())).thenReturn(stored);

        DomainIntelResult result = service.check("EXAMPLE.com");

        assertEquals(RegistrationStatus.REGISTERED, result.getStatus());
        assertEquals("database", result.getSource());
        assertTrue(result.isCached());
        assertEquals("Example Registrar", result.getRegistrar());
        assertEquals(2, result.getNameServers().size());
    }

    @Test
    void rdapRecordMeansRegistered() {
        when(probe.rdap(anyString(), anyString()))
                .thenReturn(RdapUtils.RdapResponse.ok("{\"objectClassName\":\"domain\",\"ldhName\":\"example.com\"}"));

        DomainIntelResult result = service.check("example.com");

        assertEquals(RegistrationStatus.REGISTERED, result.getStatus());
        assertEquals("rdap", result.getSource());
    }

    @Test
    void rdapReservedStatusMapsToReserved() {
        String body = "{\"handle\":\"1\",\"entities\":[],\"events\":[],\"nameservers\":[],\"status\":[\"reserved\"]}";
        when(probe.rdap(anyString(), anyString())).thenReturn(RdapUtils.RdapResponse.ok(body));

        DomainIntelResult result = service.check("example.com");

        assertEquals(RegistrationStatus.RESERVED, result.getStatus());
    }

    @Test
    void rdapNotFoundPlusWhoisNoMatchIsCandidateNotAvailable() {
        when(probe.rdap(anyString(), anyString())).thenReturn(RdapUtils.RdapResponse.notFound());
        when(probe.whois(anyString(), anyString()))
                .thenReturn(DomainProbe.WhoisResult.ok("No match for \"EXAMPLE.COM\""));

        DomainIntelResult result = service.check("example.com");

        assertEquals(RegistrationStatus.UNREGISTERED_CANDIDATE, result.getStatus());
        assertEquals("rdap+whois", result.getSource());
    }

    @Test
    void failedLookupsAreUnknownAndNeverAvailable() {
        when(probe.rdap(anyString(), anyString())).thenReturn(RdapUtils.RdapResponse.error("timeout"));
        when(probe.whois(anyString(), anyString())).thenReturn(DomainProbe.WhoisResult.failed("SocketException"));
        when(probe.dnsEnabled(anyString())).thenReturn(false);

        DomainIntelResult result = service.check("example.com");

        assertEquals(RegistrationStatus.UNKNOWN, result.getStatus());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void dnsOnlySignalIsRegisteredWithWarning() {
        when(probe.rdap(anyString(), anyString())).thenReturn(RdapUtils.RdapResponse.error("timeout"));
        when(probe.whois(anyString(), anyString())).thenReturn(DomainProbe.WhoisResult.failed("SocketException"));
        when(probe.dnsEnabled(anyString())).thenReturn(true);

        DomainIntelResult result = service.check("example.com");

        assertEquals(RegistrationStatus.REGISTERED, result.getStatus());
        assertEquals("dns", result.getSource());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("DNS only")));
    }
}
