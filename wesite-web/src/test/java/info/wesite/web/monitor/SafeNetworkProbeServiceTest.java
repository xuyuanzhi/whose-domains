package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SafeNetworkProbeServiceTest {

    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Test
    void rejectsInvalidPortsBeforeResolving() {
        AtomicInteger resolutions = new AtomicInteger();
        SafeNetworkProbeService service = serviceWithResolver((host, deadline) -> {
            resolutions.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        });

        assertThrows(IllegalArgumentException.class,
            () -> service.checkPorts("example.com", List.of(0, 443)));

        assertEquals(0, resolutions.get());
    }

    @Test
    void rejectsTooManyAndNullPortsBeforeResolving() {
        AtomicInteger resolutions = new AtomicInteger();
        SafeNetworkProbeService service = serviceWithResolver((host, deadline) -> {
            resolutions.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        });

        assertThrows(IllegalArgumentException.class,
            () -> service.checkPorts("example.com", java.util.Collections.nCopies(21, 443)));
        assertThrows(IllegalArgumentException.class,
            () -> service.checkPorts("example.com", java.util.Arrays.asList(443, null)));

        assertEquals(0, resolutions.get());
    }

    @Test
    void portProbeConnectsToApprovedAddressInsteadOfResolvingHostAgain() throws Exception {
        InetAddress approved = InetAddress.getByName("93.184.216.34");
        AtomicReference<InetAddress> connected = new AtomicReference<>();
        SafeNetworkProbeService service = serviceWith(
            (host, deadline) -> new InetAddress[] {approved},
            (address, port, timeoutMillis) -> {
                connected.set(address);
                return true;
            });

        SafeNetworkProbeService.PortProbeResult result =
            service.checkPorts("example.com", List.of(443));

        assertEquals(approved, connected.get());
        assertTrue(result.ports().get(0).open());
    }

    @Test
    void rejectsMixedPublicAndPrivateAnswersBeforeStartingConnectors() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        SafeNetworkProbeService service = serviceWith(
            (host, deadline) -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1")},
            (address, port, timeoutMillis) -> {
                connections.incrementAndGet();
                return true;
            });

        assertThrows(IOException.class, () -> service.checkPorts("example.com", List.of(443)));

        assertEquals(0, connections.get());
    }

    @Test
    void rejectsPrivateAddressesEmbeddedInIpv6TransitionFormats() throws Exception {
        for (InetAddress privateTransitionAddress : List.of(
            ipv6("0000:0000:0000:0000:0000:ffff:0a00:0001"),
            ipv6("2002:0a00:0001:0000:0000:0000:0000:0000"),
            ipv6("0064:ff9b:0000:0000:0000:0000:0a00:0001"))) {
            AtomicInteger connections = new AtomicInteger();
            SafeNetworkProbeService service = serviceWith(
                (host, deadline) -> new InetAddress[] {privateTransitionAddress},
                (address, port, timeoutMillis) -> {
                    connections.incrementAndGet();
                    return true;
                });

            assertThrows(IOException.class, () -> service.checkPorts("example.com", List.of(443)));
            assertEquals(0, connections.get());
        }
    }

    @Test
    void portResultsPreserveRequestOrderWhenTasksFinishOutOfOrder() throws Exception {
        SafeNetworkProbeService service = serviceWith(
            publicResolver(),
            (address, port, timeoutMillis) -> {
                if (port == 80) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                }
                return true;
            });

        SafeNetworkProbeService.PortProbeResult result =
            service.checkPorts("example.com", List.of(80, 443));

        assertEquals(List.of(80, 443), result.ports().stream()
            .map(SafeNetworkProbeService.PortResult::port).toList());
    }

    @Test
    void rejectedPortProbeSubmissionBecomesIOException() {
        SafeNetworkProbeService service = serviceWith(
            publicResolver(), rejectingExecutor(), defaultHttpClient(publicResolver()),
            (address, timeoutMillis) -> false,
            (address, port, timeoutMillis) -> true,
            MonitorDeadline::after);

        IOException failure = assertThrows(IOException.class,
            () -> service.checkPorts("example.com", List.of(443)));

        assertTrue(failure.getMessage().contains("capacity"));
        assertNull(failure.getCause());
    }

    @Test
    void jdkExecutorRejectionAlsoBecomesIOException() {
        SafeNetworkProbeService service = serviceWith(
            publicResolver(), jdkRejectingExecutor(), defaultHttpClient(publicResolver()),
            (address, timeoutMillis) -> false,
            (address, port, timeoutMillis) -> true,
            MonitorDeadline::after);

        IOException failure = assertThrows(IOException.class,
            () -> service.checkPorts("example.com", List.of(443)));

        assertTrue(failure.getMessage().contains("capacity"));
        assertNull(failure.getCause());
    }

    @Test
    void deadlineCancelsEveryUnfinishedPortFuture() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(2);
        SafeNetworkProbeService service = serviceWith(
            publicResolver(), executor(2), defaultHttpClient(publicResolver()),
            (address, timeoutMillis) -> false,
            (address, port, timeoutMillis) -> {
                try {
                    new CountDownLatch(1).await();
                    return true;
                } catch (InterruptedException cancelled) {
                    interrupted.countDown();
                    throw new IOException("cancelled", cancelled);
                }
            },
            ignored -> MonitorDeadline.after(Duration.ofMillis(100)));

        assertThrows(IOException.class, () -> service.checkPorts("example.com", List.of(80, 443)));

        assertTrue(interrupted.await(1, TimeUnit.SECONDS), "unfinished port probes were not cancelled");
    }

    @Test
    void pingUsesTheFirstApprovedAddressForIcmp() throws Exception {
        InetAddress approved = InetAddress.getByName("93.184.216.34");
        AtomicReference<InetAddress> reached = new AtomicReference<>();
        MonitorTargetPolicy.HostResolver resolver = (host, deadline) -> new InetAddress[] {approved};
        SafeNetworkProbeService service = serviceWith(
            resolver, executor(2), defaultHttpClient(resolver),
            (address, timeoutMillis) -> {
                reached.set(address);
                return false;
            },
            (address, port, timeoutMillis) -> false,
            MonitorDeadline::after);

        service.ping("example.com");

        assertEquals(approved, reached.get());
    }

    @Test
    void httpsFailureFallsBackToHttpWithTheSameRequestDeadline() throws Exception {
        MonitorTargetPolicy.HostResolver resolver = publicResolver();
        List<String> schemes = new ArrayList<>();
        BoundHttpClient client = new BoundHttpClient(resolver, (uri, method, address, deadline) -> {
            schemes.add(uri.getScheme());
            if ("https".equals(uri.getScheme())) {
                throw new IOException("TLS failed");
            }
            return new BoundHttpClient.Response(204, java.util.Map.of(), new byte[0]);
        });
        SafeNetworkProbeService service = serviceWith(
            resolver, executor(2), client,
            (address, timeoutMillis) -> false,
            (address, port, timeoutMillis) -> false,
            MonitorDeadline::after);

        SafeNetworkProbeService.PingProbeResult result = service.ping("example.com");

        assertEquals(List.of("https", "http"), schemes);
        assertTrue(result.httpReachable());
        assertEquals(204, result.httpStatus());
    }

    @Test
    void privateRedirectIsRejectedByBoundHttpClientBeforeAnyPrivateConnection() throws Exception {
        InetAddress approved = InetAddress.getByName("93.184.216.34");
        AtomicInteger privateConnections = new AtomicInteger();
        MonitorTargetPolicy.HostResolver resolver = (host, deadline) -> {
            if ("127.0.0.1".equals(host)) {
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            }
            return new InetAddress[] {approved};
        };
        BoundHttpClient client = new BoundHttpClient(resolver, (uri, method, address, deadline) -> {
            if (address.isLoopbackAddress()) {
                privateConnections.incrementAndGet();
            }
            if ("https".equals(uri.getScheme())) {
                return new BoundHttpClient.Response(302, java.util.Map.of("location", "http://127.0.0.1/"), new byte[0]);
            }
            return new BoundHttpClient.Response(200, java.util.Map.of(), new byte[0]);
        });
        SafeNetworkProbeService service = serviceWith(
            resolver, executor(2), client,
            (address, timeoutMillis) -> false,
            (address, port, timeoutMillis) -> false,
            MonitorDeadline::after);

        SafeNetworkProbeService.PingProbeResult result = service.ping("example.com");

        assertEquals(0, privateConnections.get());
        assertTrue(result.httpReachable());
    }

    @Test
    void boundHttpClientIsConstructedBySpringWithTheProductionResolver() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(MonitorResolverConfiguration.class, NetworkToolProbeConfiguration.class,
                MonitorAddressResolver.class, BoundHttpClient.class);
            context.refresh();

            assertNotNull(context.getBean(BoundHttpClient.class));
        }
    }

    private SafeNetworkProbeService serviceWithResolver(MonitorTargetPolicy.HostResolver resolver) {
        return serviceWith(resolver, (address, port, timeoutMillis) -> false);
    }

    private SafeNetworkProbeService serviceWith(
        MonitorTargetPolicy.HostResolver resolver,
        SafeNetworkProbeService.PortConnector connector) {
        return serviceWith(resolver, executor(4), defaultHttpClient(resolver),
            (address, timeoutMillis) -> false, connector, MonitorDeadline::after);
    }

    private SafeNetworkProbeService serviceWith(
        MonitorTargetPolicy.HostResolver resolver,
        AsyncTaskExecutor executor,
        BoundHttpClient client,
        SafeNetworkProbeService.ReachabilityChecker reachability,
        SafeNetworkProbeService.PortConnector connector,
        SafeNetworkProbeService.DeadlineFactory deadlines) {
        return new SafeNetworkProbeService(resolver, executor, client, reachability, connector, deadlines);
    }

    private BoundHttpClient defaultHttpClient(MonitorTargetPolicy.HostResolver resolver) {
        return new BoundHttpClient(resolver, (uri, method, address, deadline) ->
            new BoundHttpClient.Response(200, java.util.Map.of(), new byte[0]));
    }

    private MonitorTargetPolicy.HostResolver publicResolver() {
        return (host, deadline) -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
    }

    private ThreadPoolTaskExecutor executor(int size) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("safe-network-probe-test-");
        executor.initialize();
        executors.add(executor);
        return executor;
    }

    private static AsyncTaskExecutor rejectingExecutor() {
        return new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                throw new TaskRejectedException("capacity exhausted");
            }

            @Override
            public Future<?> submit(Runnable task) {
                throw new TaskRejectedException("capacity exhausted");
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                throw new TaskRejectedException("capacity exhausted");
            }
        };
    }

    private static AsyncTaskExecutor jdkRejectingExecutor() {
        return new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                throw new RejectedExecutionException("capacity exhausted");
            }

            @Override
            public Future<?> submit(Runnable task) {
                throw new RejectedExecutionException("capacity exhausted");
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                throw new RejectedExecutionException("capacity exhausted");
            }
        };
    }

    private static InetAddress ipv6(String hexadecimal) throws Exception {
        String[] groups = hexadecimal.split(":");
        byte[] bytes = new byte[16];
        for (int index = 0; index < groups.length; index++) {
            int value = Integer.parseInt(groups[index], 16);
            bytes[index * 2] = (byte) (value >>> 8);
            bytes[index * 2 + 1] = (byte) value;
        }
        return Inet6Address.getByAddress(null, bytes, -1);
    }
}
