package info.wesite.web.monitor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/** Safe facade for the public ping and port-probe tools. */
@Service
public final class SafeNetworkProbeService {

    private static final Duration REQUEST_BUDGET = Duration.ofSeconds(15);
    private static final int MAX_PORTS = 20;
    private static final int CONNECT_STEP_TIMEOUT_MS = 2_000;

    private final MonitorTargetPolicy.HostResolver resolver;
    private final AsyncTaskExecutor portExecutor;
    private final BoundHttpClient httpClient;
    private final ReachabilityChecker reachability;
    private final PortConnector connector;
    private final DeadlineFactory deadlines;

    @Autowired
    public SafeNetworkProbeService(
        MonitorAddressResolver resolver,
        @Qualifier(NetworkToolProbeConfiguration.PORT_EXECUTOR_BEAN) AsyncTaskExecutor portExecutor,
        BoundHttpClient httpClient) {
        this(resolver, portExecutor, httpClient,
            (address, timeoutMillis) -> address.isReachable(timeoutMillis),
            SafeNetworkProbeService::connect,
            MonitorDeadline::after);
    }

    SafeNetworkProbeService(
        MonitorTargetPolicy.HostResolver resolver,
        AsyncTaskExecutor portExecutor,
        BoundHttpClient httpClient,
        ReachabilityChecker reachability,
        PortConnector connector,
        DeadlineFactory deadlines) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.portExecutor = Objects.requireNonNull(portExecutor, "portExecutor");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.reachability = Objects.requireNonNull(reachability, "reachability");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
    }

    public PingProbeResult ping(String rawHost) throws IOException {
        MonitorDeadline deadline = deadlines.after(REQUEST_BUDGET);
        MonitorTargetPolicy.ResolvedTarget target = MonitorTargetPolicy.resolvePublic(rawHost, deadline, resolver);
        InetAddress address = target.addresses().get(0);

        long icmpStartedAt = System.nanoTime();
        boolean icmpReachable = false;
        try {
            icmpReachable = reachability.isReachable(address, deadline.timeoutMillis(CONNECT_STEP_TIMEOUT_MS));
        } catch (IOException ignored) {
            // A failed ICMP probe is a normal result; HTTP still provides a useful answer.
        }
        long icmpResponseMs = elapsedMillis(icmpStartedAt);

        HttpProbe http = probeHttp(target.host(), deadline);
        boolean online = icmpReachable || http.reachable();
        Long average = averageResponse(icmpReachable, icmpResponseMs, http.reachable(), http.responseMs());
        return new PingProbeResult(
            target.host(), address.getHostAddress(), icmpReachable, icmpResponseMs,
            http.reachable(), http.status(), http.responseMs(), online, average, speed(average));
    }

    public PortProbeResult checkPorts(String rawHost, List<Integer> ports) throws IOException {
        List<Integer> requestedPorts = validatePorts(ports);
        MonitorDeadline deadline = deadlines.after(REQUEST_BUDGET);
        MonitorTargetPolicy.ResolvedTarget target = MonitorTargetPolicy.resolvePublic(rawHost, deadline, resolver);
        List<Future<PortResult>> futures = new ArrayList<>(requestedPorts.size());
        try {
            for (Integer port : requestedPorts) {
                futures.add(portExecutor.submit(() -> probePort(target.addresses(), port, deadline)));
            }
            List<PortResult> results = new ArrayList<>(requestedPorts.size());
            for (Future<PortResult> future : futures) {
                results.add(await(future, deadline));
            }
            return new PortProbeResult(target.host(), results);
        } catch (RejectedExecutionException rejected) {
            throw new IOException("Port probe capacity exhausted");
        } finally {
            cancelUnfinished(futures);
        }
    }

    private HttpProbe probeHttp(String host, MonitorDeadline deadline) throws IOException {
        HttpProbe https = executeHttp(URI.create("https://" + host), deadline);
        if (https.reachable()) {
            return https;
        }
        deadline.throwIfExpired();
        return executeHttp(URI.create("http://" + host), deadline);
    }

    private HttpProbe executeHttp(URI uri, MonitorDeadline deadline) throws IOException {
        long startedAt = System.nanoTime();
        try {
            BoundHttpClient.Response response = httpClient.execute(uri, "HEAD", deadline);
            int status = response.status();
            return new HttpProbe(status > 0 && status < 600, status, elapsedMillis(startedAt));
        } catch (MonitorTargetPolicy.BlockedTargetException blocked) {
            throw blocked;
        } catch (IOException failed) {
            deadline.throwIfExpired();
            return new HttpProbe(false, null, null);
        }
    }

    private PortResult probePort(
        List<InetAddress> addresses,
        int port,
        MonitorDeadline deadline) throws IOException {
        long startedAt = System.nanoTime();
        for (InetAddress address : addresses) {
            try {
                if (connector.connect(address, port, deadline.timeoutMillis(CONNECT_STEP_TIMEOUT_MS))) {
                    return new PortResult(port, true, elapsedMillis(startedAt));
                }
            } catch (IOException ignored) {
                // Try each address approved by the single policy decision.
            }
        }
        return new PortResult(port, false, elapsedMillis(startedAt));
    }

    private static PortResult await(Future<PortResult> future, MonitorDeadline deadline) throws IOException {
        try {
            PortResult result = future.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            deadline.throwIfExpired();
            return result;
        } catch (TimeoutException timeout) {
            throw new SocketTimeoutException("Network probe deadline exceeded");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Network probe interrupted");
        } catch (CancellationException cancelled) {
            throw new IOException("Network probe was cancelled");
        } catch (ExecutionException failed) {
            throw new IOException("Port probe failed");
        }
    }

    private static List<Integer> validatePorts(List<Integer> ports) {
        if (ports == null || ports.isEmpty() || ports.size() > MAX_PORTS) {
            throw new IllegalArgumentException("Between one and twenty ports are required");
        }
        for (Integer port : ports) {
            if (port == null || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Each port must be between 1 and 65535");
            }
        }
        return List.copyOf(ports);
    }

    private static void cancelUnfinished(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static boolean connect(InetAddress address, int port, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMillis);
            return true;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static Long averageResponse(
        boolean icmpReachable,
        long icmpResponseMs,
        boolean httpReachable,
        Long httpResponseMs) {
        if (icmpReachable && httpReachable && httpResponseMs != null) {
            return (icmpResponseMs + httpResponseMs) / 2;
        }
        if (icmpReachable) {
            return icmpResponseMs;
        }
        return httpReachable ? httpResponseMs : null;
    }

    private static String speed(Long responseMs) {
        if (responseMs == null) {
            return "N/A";
        }
        if (responseMs < 100) {
            return "Excellent";
        }
        if (responseMs < 300) {
            return "Good";
        }
        if (responseMs < 600) {
            return "Fair";
        }
        return "Slow";
    }

    public record PingProbeResult(
        String host, String resolvedIp,
        boolean icmpReachable, long icmpResponseMs,
        boolean httpReachable, Integer httpStatus, Long httpResponseMs,
        boolean online, Long avgResponseMs, String speed) {
    }

    public record PortProbeResult(String host, List<PortResult> ports) {
        public PortProbeResult {
            ports = List.copyOf(ports);
        }
    }

    public record PortResult(int port, boolean open, long responseMs) {
    }

    @FunctionalInterface
    interface ReachabilityChecker {
        boolean isReachable(InetAddress address, int timeoutMillis) throws IOException;
    }

    @FunctionalInterface
    interface PortConnector {
        boolean connect(InetAddress address, int port, int timeoutMillis) throws IOException;
    }

    @FunctionalInterface
    interface DeadlineFactory {
        MonitorDeadline after(Duration duration);
    }

    private record HttpProbe(boolean reachable, Integer status, Long responseMs) {
    }
}
