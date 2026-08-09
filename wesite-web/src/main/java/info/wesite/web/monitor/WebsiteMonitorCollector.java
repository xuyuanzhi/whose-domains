package info.wesite.web.monitor;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Collects website availability from a completed, bounded HTTP probe. */
@Component
public class WebsiteMonitorCollector {

    private static final Duration COLLECT_TIMEOUT = Duration.ofSeconds(8);

    private final TimedHttpProbe probe;

    @Autowired
    public WebsiteMonitorCollector(MonitorAddressResolver addressResolver) {
        BoundHttpClient httpClient = new BoundHttpClient(addressResolver);
        this.probe = (domain, deadline) -> probeWebsite(domain, deadline, httpClient);
    }

    WebsiteMonitorCollector(HttpProbe probe) {
        java.util.Objects.requireNonNull(probe, "probe");
        this.probe = (domain, deadline) -> probe.status(domain);
    }

    public MonitorCollectorResult collect(String domain, int previousFailureCount) {
        return collect(domain, previousFailureCount, MonitorDeadline.after(COLLECT_TIMEOUT));
    }

    public MonitorCollectorResult collect(
        String domain,
        int previousFailureCount,
        MonitorDeadline deadline) {
        java.util.Objects.requireNonNull(deadline, "deadline");
        try {
            deadline.throwIfExpired();
            int status = probe.status(domain, deadline);
            deadline.throwIfExpired();
            if (status < 100 || status > 599) {
                return failure(MonitorCollectorResult.FailureKind.PARSE_ERROR,
                    "HTTP probe returned an invalid status");
            }
            boolean available = status >= 200 && status < 400;
            int failureCount = available ? 0 : increment(previousFailureCount);
            return MonitorCollectorResult.success(
                MonitorCollectorResult.Source.WEBSITE,
                new MonitorState(domain, Set.of(), null, null, Map.of(), available, failureCount));
        } catch (MonitorTargetPolicy.BlockedTargetException blocked) {
            return failure(MonitorCollectorResult.FailureKind.BLOCKED_TARGET, message(blocked));
        } catch (SocketTimeoutException | HttpTimeoutException timeout) {
            return failure(MonitorCollectorResult.FailureKind.TIMEOUT, message(timeout));
        } catch (UnknownHostException notFound) {
            return failure(MonitorCollectorResult.FailureKind.NOT_FOUND, message(notFound));
        } catch (Exception lookupFailure) {
            return failure(MonitorCollectorResult.FailureKind.LOOKUP_ERROR, message(lookupFailure));
        }
    }

    private static int probeWebsite(
        String domain,
        MonitorDeadline deadline,
        BoundHttpClient httpClient) throws Exception {
        IOException httpsFailure;
        try {
            return httpClient.execute(
                URI.create("https://" + domain), "HEAD", deadline).status();
        } catch (IOException failure) {
            httpsFailure = failure;
        }
        try {
            return httpClient.execute(
                URI.create("http://" + domain), "HEAD", deadline).status();
        } catch (IOException httpFailure) {
            httpFailure.addSuppressed(httpsFailure);
            throw httpFailure;
        }
    }

    private static int increment(int previousFailureCount) {
        int normalized = Math.max(0, previousFailureCount);
        return normalized == Integer.MAX_VALUE ? normalized : normalized + 1;
    }

    private static MonitorCollectorResult failure(
        MonitorCollectorResult.FailureKind kind,
        String message) {
        return MonitorCollectorResult.failure(MonitorCollectorResult.Source.WEBSITE, kind, message);
    }

    private static String message(Exception failure) {
        return StringUtils.defaultIfBlank(failure.getMessage(), failure.getClass().getSimpleName());
    }

    @FunctionalInterface
    interface HttpProbe {
        int status(String domain) throws Exception;
    }

    @FunctionalInterface
    private interface TimedHttpProbe {
        int status(String domain, MonitorDeadline deadline) throws Exception;
    }
}
