package info.wesite.web.monitor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** Collects the peer leaf certificate expiry with bounded connect/read timeouts. */
@Component
public class SslMonitorCollector {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private final CertificateProbe probe;

    public SslMonitorCollector() {
        this(SslMonitorCollector::probeCertificate);
    }

    SslMonitorCollector(CertificateProbe probe) {
        this.probe = java.util.Objects.requireNonNull(probe, "probe");
    }

    public MonitorCollectorResult collect(String domain) {
        try {
            X509Certificate certificate = probe.probe(domain);
            if (certificate == null || certificate.getNotAfter() == null) {
                return failure(MonitorCollectorResult.FailureKind.PARSE_ERROR,
                    "TLS peer did not provide a usable X.509 certificate");
            }
            return MonitorCollectorResult.success(
                MonitorCollectorResult.Source.SSL,
                new MonitorState(
                    domain,
                    Set.of(),
                    null,
                    certificate.getNotAfter().toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                    Map.of(),
                    false,
                    0));
        } catch (MonitorTargetPolicy.BlockedTargetException blocked) {
            return failure(MonitorCollectorResult.FailureKind.BLOCKED_TARGET, message(blocked));
        } catch (SocketTimeoutException timeout) {
            return failure(MonitorCollectorResult.FailureKind.TIMEOUT, message(timeout));
        } catch (UnknownHostException notFound) {
            return failure(MonitorCollectorResult.FailureKind.NOT_FOUND, message(notFound));
        } catch (Exception lookupFailure) {
            return failure(MonitorCollectorResult.FailureKind.LOOKUP_ERROR, message(lookupFailure));
        }
    }

    private static X509Certificate probeCertificate(String domain) throws Exception {
        MonitorTargetPolicy.ResolvedTarget target = MonitorTargetPolicy.resolvePublic(domain);
        IOException lastFailure = null;
        for (java.net.InetAddress address : target.addresses()) {
            try (Socket plain = new Socket()) {
                plain.connect(new InetSocketAddress(address, 443), CONNECT_TIMEOUT_MS);
                plain.setSoTimeout(READ_TIMEOUT_MS);
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket tls = (SSLSocket) factory.createSocket(
                    plain, target.host(), 443, true)) {
                    tls.setSoTimeout(READ_TIMEOUT_MS);
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(parameters);
                    tls.startHandshake();
                    Certificate[] certificates = tls.getSession().getPeerCertificates();
                    if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate leaf)) {
                        throw new IOException("TLS peer did not provide an X.509 certificate");
                    }
                    return leaf;
                }
            } catch (IOException failure) {
                lastFailure = failure;
            }
        }
        throw lastFailure == null ? new UnknownHostException(target.host()) : lastFailure;
    }

    private static MonitorCollectorResult failure(
        MonitorCollectorResult.FailureKind kind,
        String message) {
        return MonitorCollectorResult.failure(MonitorCollectorResult.Source.SSL, kind, message);
    }

    private static String message(Exception failure) {
        return StringUtils.defaultIfBlank(failure.getMessage(), failure.getClass().getSimpleName());
    }

    @FunctionalInterface
    interface CertificateProbe {
        X509Certificate probe(String domain) throws Exception;
    }
}
