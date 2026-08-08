package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainDns;

class DomainReportIndexPolicyTest {

    private final DomainReportIndexPolicy policy = new DomainReportIndexPolicy();

    @Test
    void indexesARealReportWithAtLeastTwoEvidenceGroups() {
        Domain domain = domainNamed("example.com");
        domain.setRegistrar("Example Registrar");
        domain.setRegistCreateDateText("1995-08-14");

        assertTrue(policy.isIndexable(domain, List.of()));
        assertEquals("index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1",
                policy.robotsDirective(domain, List.of()));
    }

    @Test
    void noindexesAReportThatOnlyHasANameAndStatus() {
        Domain domain = domainNamed("thin.example");

        assertFalse(policy.isIndexable(domain, List.of()));
        assertEquals("noindex, follow", policy.robotsDirective(domain, List.of()));
    }

    @Test
    void indexesReportsWithRegistryIdentityAndRdapOnlyEvidence() {
        Domain domain = domainNamed("rdap.example");
        domain.setRegistryDomainID("RDAP-123");
        domain.setRdapText("{\"objectClassName\":\"domain\"}");

        assertTrue(policy.isIndexable(domain, List.of()));
    }

    @Test
    void indexesReportsWithRegistrarAndWhoisOnlyEvidence() {
        Domain domain = domainNamed("whois.example");
        domain.setRegistrar("Example Registrar");
        domain.setWhoisText("Domain Name: WHOIS.EXAMPLE");

        assertTrue(policy.isIndexable(domain, List.of()));
    }

    @Test
    void countsNameserversAsInfrastructureEvidence() {
        Domain domain = domainNamed("nameservers.example");
        domain.setRegistrar("Example Registrar");
        domain.setNameServers("ns1.example.net,ns2.example.net");

        assertTrue(policy.isIndexable(domain, List.of()));
    }

    @Test
    void countsDnsRecordsAsInfrastructureEvidence() {
        Domain domain = domainNamed("dns.example");
        domain.setRegistrar("Example Registrar");

        assertTrue(policy.isIndexable(domain, List.of(new DomainDns())));
    }

    @Test
    void noindexesNullBlankAndNamelessDomains() {
        Domain blankName = new Domain();
        blankName.setName("   ");
        blankName.setRegistrar("Example Registrar");
        blankName.setRegistCreateDateText("1995-08-14");

        assertFalse(policy.isIndexable(null, null));
        assertFalse(policy.isIndexable(new Domain(), null));
        assertFalse(policy.isIndexable(blankName, List.of()));
        assertEquals("noindex, follow", policy.robotsDirective(null, null));
    }

    private Domain domainNamed(String name) {
        Domain domain = new Domain();
        domain.setName(name);
        domain.setStatus(Domain.STATUS_ACTIVE);
        return domain;
    }
}
