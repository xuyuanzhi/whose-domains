package info.wesite.web.monitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;
import info.wesite.core.utils.RdapUtils;
import info.wesite.core.utils.WhoisUtils;

/** Collects a fresh, comparable WHOIS/RDAP state for one domain. */
@Component
public class DomainMonitorCollector {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE_CHARS = 1_048_576;
    private static final Duration COLLECT_TIMEOUT = Duration.ofSeconds(10);

    private final TimedTextLookup rdapLookup;
    private final TimedTextLookup whoisLookup;
    private final DomainParser rdapParser;
    private final DomainParser whoisParser;

    @Autowired
    public DomainMonitorCollector(
        WhoisUtils whoisUtils,
        MonitorAddressResolver addressResolver) {
        BoundHttpClient httpClient = new BoundHttpClient(addressResolver);
        this.rdapLookup = (domain, server, deadline) ->
            readRdap(domain, server, deadline, httpClient);
        this.whoisLookup = (domain, server, deadline) ->
            readWhois(domain, server, deadline, addressResolver);
        this.rdapParser = RdapUtils::fillRdapInfoFromText;
        this.whoisParser = whoisUtils::fillWhoisInfoFromText0;
    }

    DomainMonitorCollector(
        TextLookup rdapLookup,
        TextLookup whoisLookup,
        DomainParser rdapParser,
        DomainParser whoisParser) {
        java.util.Objects.requireNonNull(rdapLookup, "rdapLookup");
        java.util.Objects.requireNonNull(whoisLookup, "whoisLookup");
        this.rdapLookup = (domain, server, deadline) -> rdapLookup.lookup(domain, server);
        this.whoisLookup = (domain, server, deadline) -> whoisLookup.lookup(domain, server);
        this.rdapParser = java.util.Objects.requireNonNull(rdapParser, "rdapParser");
        this.whoisParser = java.util.Objects.requireNonNull(whoisParser, "whoisParser");
    }

    public MonitorCollectorResult collect(Domain domain) {
        return collect(domain, MonitorDeadline.after(COLLECT_TIMEOUT));
    }

    public MonitorCollectorResult collect(Domain domain, MonitorDeadline deadline) {
        java.util.Objects.requireNonNull(deadline, "deadline");
        if (domain == null || StringUtils.isBlank(domain.getName())) {
            return failure(MonitorCollectorResult.FailureKind.NO_SOURCE, "Domain lookup seed is missing");
        }

        Attempt lastFailure = null;
        String rdapServer = firstNonBlank(domain.getRdapServer(), domain.getParentRdapServer());
        if (rdapServer != null) {
            Attempt attempt = attempt(domain, rdapServer, rdapLookup, rdapParser, true, deadline);
            if (attempt.result() != null) {
                return attempt.result();
            }
            lastFailure = attempt;
        }

        String whoisServer = domain.getFinalWhoisServer();
        if (whoisServer != null) {
            Attempt attempt = attempt(domain, whoisServer, whoisLookup, whoisParser, false, deadline);
            if (attempt.result() != null) {
                return attempt.result();
            }
            lastFailure = attempt;
        }

        if (lastFailure == null) {
            return failure(MonitorCollectorResult.FailureKind.NO_SOURCE,
                "No RDAP or WHOIS server is configured");
        }
        return failure(lastFailure.kind(), lastFailure.message());
    }

    private Attempt attempt(
        Domain seed,
        String server,
        TimedTextLookup lookup,
        DomainParser parser,
        boolean rdap,
        MonitorDeadline deadline) {
        try {
            deadline.throwIfExpired();
            String text = lookup.lookup(seed.getName(), server, deadline);
            deadline.throwIfExpired();
            if (StringUtils.isBlank(text)) {
                return new Attempt(null, MonitorCollectorResult.FailureKind.LOOKUP_ERROR,
                    (rdap ? "RDAP" : "WHOIS") + " returned no response");
            }
            if (!rdap && !WhoisUtils.isValid(text)) {
                return new Attempt(null, MonitorCollectorResult.FailureKind.NOT_FOUND,
                    "WHOIS returned no usable record");
            }

            Domain candidate = seed(seed);
            boolean parsed;
            try {
                parsed = parser.parse(candidate, text);
            } catch (RuntimeException parseFailure) {
                return new Attempt(null, MonitorCollectorResult.FailureKind.PARSE_ERROR,
                    message(parseFailure));
            }
            if (!parsed) {
                return new Attempt(null, MonitorCollectorResult.FailureKind.PARSE_ERROR,
                    (rdap ? "RDAP" : "WHOIS") + " response could not be parsed");
            }
            MonitorState normalized = state(candidate);
            if (normalized.domainStatuses().isEmpty() && normalized.domainExpiry() == null) {
                return new Attempt(null, MonitorCollectorResult.FailureKind.PARSE_ERROR,
                    (rdap ? "RDAP" : "WHOIS") + " response contained no comparable state");
            }

            copyMonitoredFields(candidate, seed, normalized);
            return new Attempt(MonitorCollectorResult.success(
                MonitorCollectorResult.Source.DOMAIN,
                normalized), null, null);
        } catch (MonitorTargetPolicy.BlockedTargetException blocked) {
            return new Attempt(null, MonitorCollectorResult.FailureKind.BLOCKED_TARGET, message(blocked));
        } catch (SocketTimeoutException timeout) {
            return new Attempt(null, MonitorCollectorResult.FailureKind.TIMEOUT, message(timeout));
        } catch (UnknownHostException notFound) {
            return new Attempt(null, MonitorCollectorResult.FailureKind.NOT_FOUND, message(notFound));
        } catch (Exception lookupFailure) {
            return new Attempt(null, MonitorCollectorResult.FailureKind.LOOKUP_ERROR, message(lookupFailure));
        }
    }

    private static String readRdap(
        String domain,
        String server,
        MonitorDeadline deadline,
        BoundHttpClient httpClient) throws Exception {
        String separator = server.endsWith("/") ? "" : "/";
        URI uri = URI.create(server + separator + "domain/" + domain);
        BoundHttpClient.Response response = httpClient.execute(uri, "GET", deadline);
        return response.status() >= 200 && response.status() < 300
            ? new String(response.body(), StandardCharsets.UTF_8)
            : null;
    }

    private static String readWhois(
        String domain,
        String server,
        MonitorDeadline deadline,
        MonitorTargetPolicy.HostResolver resolver) throws Exception {
        MonitorTargetPolicy.ResolvedTarget target = MonitorTargetPolicy.resolvePublic(
            server, deadline, resolver);
        IOException lastFailure = null;
        for (java.net.InetAddress address : target.addresses()) {
            deadline.throwIfExpired();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, 43),
                    deadline.timeoutMillis(CONNECT_TIMEOUT_MS));
                socket.setSoTimeout(deadline.timeoutMillis(READ_TIMEOUT_MS));
                try (Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8))) {
                    writer.write(domain);
                    writer.write("\r\n");
                    writer.flush();
                    return readBounded(reader, socket, deadline);
                }
            } catch (IOException failure) {
                lastFailure = failure;
            }
        }
        throw lastFailure == null ? new UnknownHostException(target.host()) : lastFailure;
    }

    private static String readBounded(
        BufferedReader reader,
        Socket socket,
        MonitorDeadline deadline) throws IOException {
        StringBuilder value = new StringBuilder();
        char[] buffer = new char[4_096];
        int count;
        while (true) {
            socket.setSoTimeout(deadline.timeoutMillis(READ_TIMEOUT_MS));
            count = reader.read(buffer);
            deadline.throwIfExpired();
            if (count < 0) {
                break;
            }
            if (value.length() + count > MAX_RESPONSE_CHARS) {
                throw new IOException("Monitoring response exceeded size limit");
            }
            value.append(buffer, 0, count);
        }
        return value.toString();
    }

    private static Domain seed(Domain source) {
        Domain candidate = new Domain();
        candidate.setName(source.getName());
        candidate.setParentRdapServer(source.getParentRdapServer());
        candidate.setRdapServer(source.getRdapServer());
        candidate.setParentWhoisServer(source.getParentWhoisServer());
        candidate.setWhoisServer(source.getWhoisServer());
        return candidate;
    }

    private static void copyMonitoredFields(
        Domain source,
        Domain target,
        MonitorState normalized) {
        target.setRegistrar(source.getRegistrar());
        if (!normalized.domainStatuses().isEmpty()) {
            target.setDomainStatus(source.getDomainStatus());
        }
        if (normalized.domainExpiry() != null) {
            target.setRegistExpiryDateText(source.getRegistExpiryDateText());
        }
        if (StringUtils.isNotBlank(source.getRdapServer())) {
            target.setRdapServer(source.getRdapServer());
        }
    }

    private static MonitorState state(Domain domain) {
        return new MonitorState(
            domain.getName(),
            statuses(domain.getDomainStatus()),
            expiry(domain),
            null,
            Map.of(),
            false,
            0);
    }

    private static LocalDate expiry(Domain domain) {
        String value = domain.getRegistExpiryDateText();
        if (StringUtils.isNotBlank(value)) {
            try {
                if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
                    return LocalDate.parse(value.substring(0, 10));
                }
                if (value.length() >= 8 && value.substring(0, 8).chars().allMatch(Character::isDigit)) {
                    return LocalDate.parse(value.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
                }
            } catch (DateTimeParseException ignored) {
                // Fall through to the legacy parsed Date.
            }
        }
        return domain.getExpiryDate() == null
            ? null
            : domain.getExpiryDate().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static Set<String> statuses(String value) {
        if (StringUtils.isBlank(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split("[,\\s]+"))
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.isNotBlank(first)) {
            return first.trim();
        }
        return StringUtils.isBlank(second) ? null : second.trim();
    }

    private static MonitorCollectorResult failure(
        MonitorCollectorResult.FailureKind kind,
        String message) {
        return MonitorCollectorResult.failure(MonitorCollectorResult.Source.DOMAIN, kind, message);
    }

    private static String message(Exception failure) {
        return StringUtils.defaultIfBlank(failure.getMessage(), failure.getClass().getSimpleName());
    }

    @FunctionalInterface
    interface TextLookup {
        String lookup(String domain, String server) throws Exception;
    }

    @FunctionalInterface
    private interface TimedTextLookup {
        String lookup(String domain, String server, MonitorDeadline deadline) throws Exception;
    }

    @FunctionalInterface
    interface DomainParser {
        boolean parse(Domain target, String text);
    }

    private record Attempt(
        MonitorCollectorResult result,
        MonitorCollectorResult.FailureKind kind,
        String message) {
    }
}
