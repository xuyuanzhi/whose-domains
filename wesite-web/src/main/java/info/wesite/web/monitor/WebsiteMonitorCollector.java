package info.wesite.web.monitor;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** Collects website availability from a completed, bounded HTTP probe. */
@Component
public class WebsiteMonitorCollector {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_REDIRECTS = 3;

    private final HttpProbe probe;

    public WebsiteMonitorCollector() {
        this(WebsiteMonitorCollector::probeWebsite);
    }

    WebsiteMonitorCollector(HttpProbe probe) {
        this.probe = java.util.Objects.requireNonNull(probe, "probe");
    }

    public MonitorCollectorResult collect(String domain, int previousFailureCount) {
        try {
            int status = probe.status(domain);
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

    private static int probeWebsite(String domain) throws Exception {
        IOException httpsFailure;
        try {
            return probe(URI.create("https://" + domain), MAX_REDIRECTS);
        } catch (IOException failure) {
            httpsFailure = failure;
        }
        try {
            return probe(URI.create("http://" + domain), MAX_REDIRECTS);
        } catch (IOException httpFailure) {
            httpFailure.addSuppressed(httpsFailure);
            throw httpFailure;
        }
    }

    private static int probe(URI uri, int redirectsRemaining) throws Exception {
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
            || uri.getHost() == null) {
            throw new MonitorTargetPolicy.BlockedTargetException("Website target must be HTTP(S)");
        }
        MonitorTargetPolicy.resolvePublic(uri.getHost());

        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "WhoseDomains-Monitor/1.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400 && redirectsRemaining > 0) {
                String location = connection.getHeaderField("Location");
                if (StringUtils.isNotBlank(location)) {
                    return probe(uri.resolve(location), redirectsRemaining - 1);
                }
            }
            return status;
        } finally {
            connection.disconnect();
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
}
