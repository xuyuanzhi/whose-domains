package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Type;

import info.wesite.core.entity.Domain;

class MonitorCollectorTest {

    @Test
    void domainCollectorFallsBackFromUnparseableRdapToWhois() {
        Domain domain = domain();
        domain.setParentRdapServer("https://rdap.example/");
        domain.setParentWhoisServer("whois.example");
        DomainMonitorCollector collector = new DomainMonitorCollector(
            (name, server) -> "invalid rdap",
            (name, server) -> "Domain Status: clientTransferProhibited",
            (target, text) -> false,
            (target, text) -> {
                target.setDomainStatus("clientTransferProhibited,ok");
                target.setRegistExpiryDateText("2027-01-03T00:00:00Z");
                target.setRegistrar("Example Registrar");
                return true;
            });

        MonitorCollectorResult result = collector.collect(domain);

        assertTrue(result.successful());
        assertEquals(MonitorCollectorResult.Source.DOMAIN, result.source());
        assertEquals(Set.of("clientTransferProhibited", "ok"), result.state().domainStatuses());
        assertEquals(LocalDate.of(2027, 1, 3), result.state().domainExpiry());
        assertEquals("Example Registrar", domain.getRegistrar());
    }

    @Test
    void domainParserErrorProducesFailureMetadataWithoutInventingState() {
        Domain domain = domain();
        domain.setParentRdapServer("https://rdap.example/");
        DomainMonitorCollector collector = new DomainMonitorCollector(
            (name, server) -> "malformed",
            (name, server) -> null,
            (target, text) -> { throw new IllegalArgumentException("invalid JSON"); },
            (target, text) -> false);

        MonitorCollectorResult result = collector.collect(domain);

        assertFalse(result.successful());
        assertNull(result.state());
        assertEquals(MonitorCollectorResult.FailureKind.PARSE_ERROR, result.failureKind());
        assertTrue(result.failureMessage().contains("invalid JSON"));
    }

    @Test
    void domainParserThatProducesNoComparableFieldsIsAParseFailure() {
        Domain domain = domain();
        domain.setParentRdapServer("https://rdap.example/");
        DomainMonitorCollector collector = new DomainMonitorCollector(
            (name, server) -> "{}",
            (name, server) -> null,
            (target, text) -> true,
            (target, text) -> false);

        MonitorCollectorResult result = collector.collect(domain);

        assertFalse(result.successful());
        assertEquals(MonitorCollectorResult.FailureKind.PARSE_ERROR, result.failureKind());
    }

    @Test
    void invalidExpiryDoesNotReplaceThePreviouslyNormalizedDomainExpiry() {
        Domain domain = domain();
        domain.setRegistExpiryDateText("2027-01-03");
        domain.setParentRdapServer("https://rdap.example/");
        DomainMonitorCollector collector = new DomainMonitorCollector(
            (name, server) -> "response",
            (name, server) -> null,
            (target, text) -> {
                target.setDomainStatus("ok");
                target.setRegistExpiryDateText("N/A");
                return true;
            },
            (target, text) -> false);

        MonitorCollectorResult result = collector.collect(domain);

        assertTrue(result.successful());
        assertEquals(Set.of("ok"), result.state().domainStatuses());
        assertNull(result.state().domainExpiry());
        assertEquals("2027-01-03", domain.getRegistExpiryDateText());
    }

    @Test
    void dnsCollectorCanonicalizesSupportedRecordValues() throws Exception {
        Name owner = Name.fromString("Example.COM.");
        DnsMonitorCollector collector = new DnsMonitorCollector((domain, type) -> switch (type) {
            case Type.NS -> answer(new NSRecord(owner, DClass.IN, 300,
                Name.fromString("NS2.Example.COM.")));
            case Type.A -> answer(new ARecord(owner, DClass.IN, 60,
                InetAddress.getByName("203.0.113.8")));
            case Type.AAAA -> answer(new AAAARecord(owner, DClass.IN, 60,
                InetAddress.getByName("2001:db8::1")));
            case Type.MX -> answer(new MXRecord(owner, DClass.IN, 60, 10,
                Name.fromString("MAIL.Example.COM.")));
            default -> throw new AssertionError("unexpected type " + type);
        });

        MonitorCollectorResult result = collector.collect(" Example.COM. ");

        assertTrue(result.successful());
        assertEquals(Map.of(
            "A", Set.of("203.0.113.8"),
            "AAAA", Set.of("2001:db8:0:0:0:0:0:1"),
            "MX", Set.of("10 mail.example.com"),
            "NS", Set.of("ns2.example.com")), result.state().dnsRecords());
    }

    @Test
    void dnsNxdomainIsFailureRatherThanAnEmptySuccessfulAnswer() {
        DnsMonitorCollector collector = new DnsMonitorCollector((domain, type) ->
            new DnsMonitorCollector.Answer(Lookup.HOST_NOT_FOUND, List.of(), "host not found"));

        MonitorCollectorResult result = collector.collect("missing.example");

        assertFalse(result.successful());
        assertNull(result.state());
        assertEquals(MonitorCollectorResult.FailureKind.NOT_FOUND, result.failureKind());
    }

    @Test
    void dnsRetryTimeoutHasTimeoutFailureMetadata() {
        DnsMonitorCollector collector = new DnsMonitorCollector((domain, type) ->
            new DnsMonitorCollector.Answer(Lookup.TRY_AGAIN, List.of(), "query timeout"));

        MonitorCollectorResult result = collector.collect("example.com");

        assertFalse(result.successful());
        assertEquals(MonitorCollectorResult.FailureKind.TIMEOUT, result.failureKind());
    }

    @Test
    void sslCollectorExtractsCertificateExpiryInUtc() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getNotAfter()).thenReturn(Date.from(Instant.parse("2027-02-04T23:30:00Z")));
        SslMonitorCollector collector = new SslMonitorCollector(domain -> certificate);

        MonitorCollectorResult result = collector.collect("example.com");

        assertTrue(result.successful());
        assertEquals(LocalDate.of(2027, 2, 4), result.state().sslExpiry());
    }

    @Test
    void sslTimeoutHasExplicitFailureMetadata() {
        SslMonitorCollector collector = new SslMonitorCollector(domain -> {
            throw new SocketTimeoutException("TLS handshake timed out");
        });

        MonitorCollectorResult result = collector.collect("example.com");

        assertFalse(result.successful());
        assertNull(result.state());
        assertEquals(MonitorCollectorResult.FailureKind.TIMEOUT, result.failureKind());
    }

    @Test
    void websiteCollectorUsesHttpSuccessRangeAndMaintainsFailureCount() {
        MonitorCollectorResult redirect = new WebsiteMonitorCollector(domain -> 399)
            .collect("example.com", 7);
        MonitorCollectorResult clientError = new WebsiteMonitorCollector(domain -> 400)
            .collect("example.com", 1);

        assertTrue(redirect.successful());
        assertTrue(redirect.state().websiteAvailable());
        assertEquals(0, redirect.state().websiteFailureCount());
        assertTrue(clientError.successful());
        assertFalse(clientError.state().websiteAvailable());
        assertEquals(2, clientError.state().websiteFailureCount());
    }

    @Test
    void websiteTimeoutDoesNotAdvanceFailureCount() {
        WebsiteMonitorCollector collector = new WebsiteMonitorCollector(domain -> {
            throw new HttpTimeoutException("request timed out");
        });

        MonitorCollectorResult result = collector.collect("example.com", 1);

        assertFalse(result.successful());
        assertNull(result.state());
        assertEquals(MonitorCollectorResult.FailureKind.TIMEOUT, result.failureKind());
    }

    @Test
    void networkTargetPolicyRejectsPrivateAndSpecialPurposeAddresses() throws Exception {
        assertFalse(MonitorTargetPolicy.isPublic(InetAddress.getByName("127.0.0.1")));
        assertFalse(MonitorTargetPolicy.isPublic(InetAddress.getByName("10.0.0.1")));
        assertFalse(MonitorTargetPolicy.isPublic(InetAddress.getByName("100.64.0.1")));
        assertFalse(MonitorTargetPolicy.isPublic(InetAddress.getByName("fc00::1")));
        assertTrue(MonitorTargetPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void boundHttpRedirectRevalidatesDnsAndNeverConnectsToAReboundPrivateAddress() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        List<InetAddress> connected = new ArrayList<>();
        BoundHttpClient client = new BoundHttpClient(
            (host, ignoredDeadline) -> resolutions.incrementAndGet() == 1
                ? new InetAddress[] {InetAddress.getByName("8.8.8.8")}
                : new InetAddress[] {InetAddress.getByName("127.0.0.1")},
            (uri, method, address, deadline) -> {
                connected.add(address);
                return new BoundHttpClient.Response(
                    302, Map.of("location", "/next"), new byte[0]);
            });

        assertThrows(
            MonitorTargetPolicy.BlockedTargetException.class,
            () -> client.execute(
                URI.create("https://example.com/start"),
                "GET",
                MonitorDeadline.after(Duration.ofSeconds(1))));

        assertEquals(2, resolutions.get());
        assertEquals(List.of(InetAddress.getByName("8.8.8.8")), connected);
    }

    @Test
    void boundHttpMultiAddressAttemptsShareOneOverallDeadline() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicInteger attempts = new AtomicInteger();
        MonitorDeadline deadline = MonitorDeadline.forTest(
            Duration.ofMillis(100), now::get);
        BoundHttpClient client = new BoundHttpClient(
            (host, ignoredDeadline) -> new InetAddress[] {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1")},
            (uri, method, address, ignored) -> {
                attempts.incrementAndGet();
                now.addAndGet(Duration.ofMillis(101).toNanos());
                throw new IOException("first address failed");
            });

        assertThrows(SocketTimeoutException.class, () -> client.execute(
            URI.create("https://example.com/"), "HEAD", deadline));
        assertEquals(1, attempts.get());
    }

    @Test
    void boundHttpRedirectsShareOneOverallDeadline() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicInteger attempts = new AtomicInteger();
        MonitorDeadline deadline = MonitorDeadline.forTest(
            Duration.ofMillis(100), now::get);
        BoundHttpClient client = new BoundHttpClient(
            (host, ignoredDeadline) -> {
                if (attempts.get() > 0) {
                    now.addAndGet(Duration.ofMillis(50).toNanos());
                }
                return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            },
            (uri, method, address, ignored) -> {
                attempts.incrementAndGet();
                now.addAndGet(Duration.ofMillis(60).toNanos());
                return new BoundHttpClient.Response(
                    302, Map.of("location", "/next"), new byte[0]);
            });

        assertThrows(SocketTimeoutException.class, () -> client.execute(
            URI.create("https://example.com/start"), "GET", deadline));
        assertEquals(1, attempts.get());
    }

    @Test
    void boundHttpResolutionConsumesTheParentDeadlineBeforeAnyConnection() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        MonitorDeadline deadline = MonitorDeadline.forTest(
            Duration.ofMillis(100), now::get);
        BoundHttpClient client = new BoundHttpClient(
            (host, parentDeadline) -> {
                now.addAndGet(Duration.ofMillis(101).toNanos());
                parentDeadline.throwIfExpired();
                return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            },
            (uri, method, address, ignored) -> {
                connections.incrementAndGet();
                return new BoundHttpClient.Response(200, Map.of(), new byte[0]);
            });

        assertThrows(SocketTimeoutException.class, () -> client.execute(
            URI.create("https://example.com/"), "HEAD", deadline));
        assertEquals(0, connections.get());
    }

    @Test
    void everyCollectorRejectsAResultThatArrivesAfterItsOverallDeadline() throws Exception {
        AtomicLong domainNow = new AtomicLong();
        AtomicInteger whoisCalls = new AtomicInteger();
        Domain domain = domain();
        domain.setParentRdapServer("https://rdap.example/");
        domain.setParentWhoisServer("whois.example");
        DomainMonitorCollector domainCollector = new DomainMonitorCollector(
            (name, server) -> {
                domainNow.addAndGet(Duration.ofMillis(101).toNanos());
                return "{}";
            },
            (name, server) -> {
                whoisCalls.incrementAndGet();
                return "record";
            },
            (target, text) -> true,
            (target, text) -> true);
        MonitorCollectorResult domainResult = domainCollector.collect(
            domain, MonitorDeadline.forTest(Duration.ofMillis(100), domainNow::get));
        assertEquals(MonitorCollectorResult.FailureKind.TIMEOUT, domainResult.failureKind());
        assertEquals(0, whoisCalls.get());

        AtomicLong dnsNow = new AtomicLong();
        AtomicInteger dnsCalls = new AtomicInteger();
        DnsMonitorCollector dnsCollector = new DnsMonitorCollector((name, type) -> {
            dnsCalls.incrementAndGet();
            dnsNow.addAndGet(Duration.ofMillis(101).toNanos());
            return new DnsMonitorCollector.Answer(Lookup.TYPE_NOT_FOUND, List.of(), null);
        });
        MonitorCollectorResult dnsResult = dnsCollector.collect(
            "example.com", MonitorDeadline.forTest(Duration.ofMillis(100), dnsNow::get));
        assertEquals(MonitorCollectorResult.FailureKind.TIMEOUT, dnsResult.failureKind());
        assertEquals(1, dnsCalls.get());

        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getNotAfter()).thenReturn(Date.from(Instant.parse("2027-02-04T00:00:00Z")));
        AtomicLong sslNow = new AtomicLong();
        SslMonitorCollector sslCollector = new SslMonitorCollector(name -> {
            sslNow.addAndGet(Duration.ofMillis(101).toNanos());
            return certificate;
        });
        MonitorCollectorResult sslResult = sslCollector.collect(
            "example.com", MonitorDeadline.forTest(Duration.ofMillis(100), sslNow::get));
        assertEquals(MonitorCollectorResult.FailureKind.TIMEOUT, sslResult.failureKind());

        AtomicLong websiteNow = new AtomicLong();
        WebsiteMonitorCollector websiteCollector = new WebsiteMonitorCollector(name -> {
            websiteNow.addAndGet(Duration.ofMillis(101).toNanos());
            return 200;
        });
        MonitorCollectorResult websiteResult = websiteCollector.collect(
            "example.com", 0,
            MonitorDeadline.forTest(Duration.ofMillis(100), websiteNow::get));
        assertEquals(MonitorCollectorResult.FailureKind.TIMEOUT, websiteResult.failureKind());
    }

    private static DnsMonitorCollector.Answer answer(org.xbill.DNS.Record record) {
        return new DnsMonitorCollector.Answer(Lookup.SUCCESSFUL, List.of(record), null);
    }

    private static Domain domain() {
        Domain domain = new Domain();
        domain.setName("example.com");
        return domain;
    }
}
