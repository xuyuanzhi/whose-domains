package info.wesite.web.monitor;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/** Collects NS, A, AAAA and MX records as stable comparable values. */
@Component
public class DnsMonitorCollector {

    private static final int[] RECORD_TYPES = {Type.NS, Type.A, Type.AAAA, Type.MX};
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final DnsQuery query;

    public DnsMonitorCollector() {
        this(DnsMonitorCollector::queryDns);
    }

    DnsMonitorCollector(DnsQuery query) {
        this.query = java.util.Objects.requireNonNull(query, "query");
    }

    public MonitorCollectorResult collect(String rawDomain) {
        String domain = canonicalDomain(rawDomain);
        if (domain.isEmpty()) {
            return failure(MonitorCollectorResult.FailureKind.NOT_FOUND, "Domain is missing");
        }

        Map<String, Set<String>> records = new LinkedHashMap<>();
        try {
            for (int type : RECORD_TYPES) {
                Answer answer = query.lookup(domain, type);
                if (answer == null) {
                    return failure(MonitorCollectorResult.FailureKind.LOOKUP_ERROR,
                        "DNS returned no answer metadata");
                }
                if (answer.result() == Lookup.HOST_NOT_FOUND) {
                    return failure(MonitorCollectorResult.FailureKind.NOT_FOUND,
                        valueOrDefault(answer.error(), "NXDOMAIN"));
                }
                if (answer.result() == Lookup.TRY_AGAIN) {
                    MonitorCollectorResult.FailureKind kind = containsTimeout(answer.error())
                        ? MonitorCollectorResult.FailureKind.TIMEOUT
                        : MonitorCollectorResult.FailureKind.LOOKUP_ERROR;
                    return failure(kind, valueOrDefault(answer.error(), "Temporary DNS failure"));
                }
                if (answer.result() != Lookup.SUCCESSFUL && answer.result() != Lookup.TYPE_NOT_FOUND) {
                    return failure(MonitorCollectorResult.FailureKind.LOOKUP_ERROR,
                        valueOrDefault(answer.error(), "DNS lookup failed"));
                }

                Set<String> values = answer.result() == Lookup.TYPE_NOT_FOUND
                    ? Set.of()
                    : answer.records().stream()
                        .map(DnsMonitorCollector::canonicalValue)
                        .filter(value -> !value.isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                records.put(Type.string(type), values);
            }
        } catch (SocketTimeoutException timeout) {
            return failure(MonitorCollectorResult.FailureKind.TIMEOUT, message(timeout));
        } catch (Exception lookupFailure) {
            return failure(MonitorCollectorResult.FailureKind.LOOKUP_ERROR, message(lookupFailure));
        }

        return MonitorCollectorResult.success(
            MonitorCollectorResult.Source.DNS,
            new MonitorState(domain, Set.of(), null, null, records, false, 0));
    }

    private static Answer queryDns(String domain, int type) throws Exception {
        SimpleResolver resolver = new SimpleResolver();
        resolver.setTimeout(LOOKUP_TIMEOUT);
        Lookup lookup = new Lookup(domain, type);
        lookup.setResolver(resolver);
        org.xbill.DNS.Record[] response = lookup.run();
        List<org.xbill.DNS.Record> records = response == null
            ? List.of()
            : List.of(response);
        return new Answer(lookup.getResult(), records, lookup.getErrorString());
    }

    private static String canonicalValue(org.xbill.DNS.Record record) {
        if (record instanceof ARecord address) {
            return address.getAddress().getHostAddress().toLowerCase(Locale.ROOT);
        }
        if (record instanceof AAAARecord address) {
            return address.getAddress().getHostAddress().toLowerCase(Locale.ROOT);
        }
        if (record instanceof NSRecord nameserver) {
            return canonicalName(nameserver.getTarget().toString());
        }
        if (record instanceof MXRecord mail) {
            return mail.getPriority() + " " + canonicalName(mail.getTarget().toString());
        }
        return record == null ? "" : record.rdataToString().trim();
    }

    private static String canonicalName(String value) {
        String result = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String canonicalDomain(String value) {
        return canonicalName(value);
    }

    private static boolean containsTimeout(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("timeout") || normalized.contains("timed out");
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String message(Exception failure) {
        return valueOrDefault(failure.getMessage(), failure.getClass().getSimpleName());
    }

    private static MonitorCollectorResult failure(
        MonitorCollectorResult.FailureKind kind,
        String message) {
        return MonitorCollectorResult.failure(MonitorCollectorResult.Source.DNS, kind, message);
    }

    @FunctionalInterface
    interface DnsQuery {
        Answer lookup(String domain, int type) throws Exception;
    }

    public record Answer(int result, List<org.xbill.DNS.Record> records, String error) {
        public Answer {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}
