package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
import org.mockito.MockedStatic;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import info.wesite.web.controller.api.PingTestController;
import info.wesite.web.controller.api.PortCheckerController;
import info.wesite.web.controller.api.QueryHistoryRecorder;

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
    void rejectsEmptyPortListBeforeResolving() {
        AtomicInteger resolutions = new AtomicInteger();
        SafeNetworkProbeService service = serviceWithResolver((host, deadline) -> {
            resolutions.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        });

        assertThrows(IllegalArgumentException.class,
            () -> service.checkPorts("example.com", List.of()));

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
    void pingAndPortAcceptBareAndBracketedPublicIpv6WithBracketedHttpAuthorities() throws Exception {
        String literal = "2606:4700:4700::1111";
        List<String> resolvedHosts = new ArrayList<>();
        List<URI> requestedUris = new ArrayList<>();
        List<InetAddress> portConnections = new ArrayList<>();
        MonitorTargetPolicy.HostResolver literalResolver = (host, deadline) -> {
            resolvedHosts.add(host);
            return InetAddress.getAllByName(host);
        };
        BoundHttpClient client = new BoundHttpClient(literalResolver, (uri, method, address, deadline) -> {
            requestedUris.add(uri);
            if ("https".equals(uri.getScheme())) {
                throw new IOException("TLS unavailable");
            }
            return new BoundHttpClient.Response(204, java.util.Map.of(), new byte[0]);
        });
        SafeNetworkProbeService service = serviceWith(
            literalResolver, executor(1), client,
            (address, timeoutMillis) -> false,
            (address, port, timeoutMillis) -> {
                portConnections.add(address);
                return true;
            },
            MonitorDeadline::after);

        for (String rawHost : List.of(literal, "[" + literal + "]")) {
            SafeNetworkProbeService.PingProbeResult result = service.ping(rawHost);
            SafeNetworkProbeService.PortProbeResult ports = service.checkPorts(rawHost, List.of(443));

            assertEquals(literal, result.host());
            assertTrue(result.httpReachable());
            assertEquals(literal, ports.host());
            assertTrue(ports.ports().get(0).open());
        }

        assertEquals(List.of(literal, literal, literal, literal, literal, literal, literal, literal), resolvedHosts);
        assertEquals(List.of(
            "https://[" + literal + "]", "http://[" + literal + "]",
            "https://[" + literal + "]", "http://[" + literal + "]"),
            requestedUris.stream().map(URI::toString).toList());
        assertEquals(2, portConnections.size());
        assertTrue(portConnections.stream().allMatch(MonitorTargetPolicy::isPublic));
    }

    @Test
    void rejectsPrivateAndTransitionIpv6LiteralsAfterNormalizationAndResolution() throws Exception {
        for (String rawHost : List.of(
            "[fd00::1]",
            "fe80::1",
            "[2002:0a00:0001::1]",
            "[64:ff9b::0a00:0001]")) {
            AtomicInteger resolutions = new AtomicInteger();
            AtomicInteger connections = new AtomicInteger();
            SafeNetworkProbeService service = serviceWith(
                (host, deadline) -> {
                    resolutions.incrementAndGet();
                    return InetAddress.getAllByName(host);
                },
                (address, port, timeoutMillis) -> {
                    connections.incrementAndGet();
                    return true;
                });

            assertThrows(MonitorTargetPolicy.BlockedTargetException.class,
                () -> service.checkPorts(rawHost, List.of(443)), rawHost);
            assertEquals(1, resolutions.get(), rawHost + " must reach the real address policy");
            assertEquals(0, connections.get(), rawHost + " must never reach the connector");
        }
    }

    @Test
    void rejectsIpv4MappedIpv6BeforeTheJdkCanCollapseItToPublicIpv4() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        SafeNetworkProbeService service = serviceWith(
            (host, deadline) -> {
                resolutions.incrementAndGet();
                return InetAddress.getAllByName(host);
            },
            (address, port, timeoutMillis) -> {
                connections.incrementAndGet();
                return true;
            });

        for (String mapped : List.of("::ffff:8.8.8.8", "::ffff:192.0.2.1")) {
            assertThrows(IOException.class, () -> service.checkPorts(mapped, List.of(443)));
        }
        assertEquals(0, resolutions.get(), "mapped literals must be rejected before the JDK erases their form");
        assertEquals(0, connections.get());
    }

    @Test
    void rejectsIpv4TailBeforeCompressionWithoutResolutionOrConnection() throws Exception {
        AtomicInteger rejections = new AtomicInteger();
        AtomicInteger resolverCalls = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        SafeNetworkProbeService service = serviceWith(
            (host, deadline) -> {
                resolverCalls.incrementAndGet();
                return new InetAddress[] {InetAddress.getByAddress(new byte[] {8, 8, 8, 8})};
            },
            (address, port, timeoutMillis) -> {
                connections.incrementAndGet();
                return true;
            });

        for (String invalid : List.of("192.0.2.1::", "1:2:3:4:5:192.0.2.1::")) {
            try {
                service.checkPorts(invalid, List.of(443));
            } catch (java.net.UnknownHostException expected) {
                rejections.incrementAndGet();
            }
        }

        assertAll(
            () -> assertEquals(2, rejections.get(), "both malformed literals must be rejected locally"),
            () -> assertEquals(0, resolverCalls.get(), "malformed literals must not reach the resolver"),
            () -> assertEquals(0, connections.get(), "malformed literals must not reach the connector"));
    }

    @Test
    void rejectsMalformedColonAndZoneIdWithoutNameServiceOrInjectedResolverCalls() throws Exception {
        assertFalse(MonitorTargetPolicy.isPublic(InetAddress.getByAddress(new byte[] {0, 0, 0, 0})));
        AtomicInteger nameServiceCalls = new AtomicInteger();
        AtomicInteger resolverCalls = new AtomicInteger();
        List<String> invalidHosts = List.of(
            "zzzz:invalid",
            "\uff12001:db8::1",
            "2001:db8::1::2",
            "1:2:3:4:5:6:7:8:9",
            "fe80::1%1",
            "[fe80::1%eth0]");

        try (MockedStatic<InetAddress> inetAddress = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
            for (String invalid : List.of(
                "zzzz:invalid", "\uff12001:db8::1", "2001:db8::1::2", "1:2:3:4:5:6:7:8:9",
                "fe80::1%1", "fe80::1%eth0")) {
                inetAddress.when(() -> InetAddress.getByName(invalid)).thenAnswer(invocation -> {
                    nameServiceCalls.incrementAndGet();
                    throw new java.net.UnknownHostException(invalid);
                });
                inetAddress.when(() -> InetAddress.getAllByName(invalid)).thenAnswer(invocation -> {
                    nameServiceCalls.incrementAndGet();
                    throw new java.net.UnknownHostException(invalid);
                });
            }

            long startedAt = System.nanoTime();
            for (String invalid : invalidHosts) {
                assertThrows(java.net.UnknownHostException.class, () -> MonitorTargetPolicy.resolvePublic(
                    invalid,
                    MonitorDeadline.after(Duration.ofMillis(100)),
                    (host, deadline) -> {
                        resolverCalls.incrementAndGet();
                        return new InetAddress[] {InetAddress.getByAddress(new byte[] {8, 8, 8, 8})};
                    }));
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() < 500,
                "malformed literals must fail locally without blocking");
        }

        assertEquals(0, nameServiceCalls.get(), "literal validation must never enter name service");
        assertEquals(0, resolverCalls.get(), "invalid literals must fail before the injected resolver");
    }

    @Test
    void acceptsCompressedIpv6WithEmbeddedIpv4TailWithoutPreResolutionLookup() throws Exception {
        AtomicInteger resolverCalls = new AtomicInteger();
        byte[] publicAddress = new byte[] {
            0x2a, 0x00, 0x14, 0x50, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8};

        for (String rawHost : List.of("2A00:1450::8.8.8.8", "[2a00:1450::8.8.8.8]")) {
            MonitorTargetPolicy.ResolvedTarget target = MonitorTargetPolicy.resolvePublic(
                rawHost,
                MonitorDeadline.after(Duration.ofMillis(100)),
                (host, deadline) -> {
                    resolverCalls.incrementAndGet();
                    assertEquals("2a00:1450::8.8.8.8", host);
                    return new InetAddress[] {InetAddress.getByAddress(publicAddress)};
                });

            assertEquals("2a00:1450::8.8.8.8", target.host());
        }

        assertEquals(2, resolverCalls.get());
    }

    @Test
    void pingAndPortConnectionsUseOnlyPolicyApprovedAddresses() throws Exception {
        InetAddress approved = InetAddress.getByName("93.184.216.34");
        InetAddress privateAddress = InetAddress.getByName("10.0.0.1");
        AtomicInteger resolverCalls = new AtomicInteger();
        AtomicInteger pingLookups = new AtomicInteger();
        AtomicInteger portLookups = new AtomicInteger();
        List<InetAddress> icmpConnections = Collections.synchronizedList(new ArrayList<>());
        List<InetAddress> httpConnections = Collections.synchronizedList(new ArrayList<>());
        List<InetAddress> portConnections = Collections.synchronizedList(new ArrayList<>());
        MonitorTargetPolicy.HostResolver requestResolver = (host, deadline) -> {
            resolverCalls.incrementAndGet();
            AtomicInteger lookups = switch (host) {
                case "ping.example" -> pingLookups;
                case "ports.example" -> portLookups;
                default -> throw new IOException("unexpected host");
            };
            return new InetAddress[] {lookups.incrementAndGet() == 1 ? approved : privateAddress};
        };
        BoundHttpClient client = new BoundHttpClient(
            (host, deadline) -> {
                resolverCalls.incrementAndGet();
                assertEquals("ping.example", host);
                return new InetAddress[] {approved};
            },
            (uri, method, address, deadline) -> {
                httpConnections.add(address);
                return new BoundHttpClient.Response(200, java.util.Map.of(), new byte[0]);
            });
        SafeNetworkProbeService service = serviceWith(
            requestResolver, executor(1), client,
            (address, timeoutMillis) -> {
                icmpConnections.add(address);
                return true;
            },
            (address, port, timeoutMillis) -> {
                portConnections.add(address);
                return true;
            },
            MonitorDeadline::after);

        SafeNetworkProbeService.PingProbeResult ping = service.ping("ping.example");
        SafeNetworkProbeService.PortProbeResult ports = service.checkPorts("ports.example", List.of(443));

        assertTrue(ping.online());
        assertTrue(ports.ports().get(0).open());
        assertEquals(3, resolverCalls.get(), "one policy lookup per probe boundary");
        assertEquals(List.of(approved), icmpConnections);
        assertEquals(List.of(approved), httpConnections);
        assertEquals(List.of(approved), portConnections);
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
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch secondTaskFinished = new CountDownLatch(1);
        List<Integer> completionOrder = Collections.synchronizedList(new ArrayList<>());
        SafeNetworkProbeService service = serviceWith(
            publicResolver(),
            (address, port, timeoutMillis) -> {
                if (port == 80) {
                    firstTaskStarted.countDown();
                    try {
                        if (!secondTaskFinished.await(1, TimeUnit.SECONDS)) {
                            throw new IOException("second port probe did not finish");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                } else {
                    try {
                        if (!firstTaskStarted.await(1, TimeUnit.SECONDS)) {
                            throw new IOException("first port probe did not start");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                    completionOrder.add(port);
                    secondTaskFinished.countDown();
                    return true;
                }
                completionOrder.add(port);
                return true;
            });

        SafeNetworkProbeService.PortProbeResult result =
            service.checkPorts("example.com", List.of(80, 443));

        assertEquals(List.of(443, 80), completionOrder);
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
    void interruptingPortCallerCancelsEveryUnfinishedPortFuture() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SafeNetworkProbeService service = serviceWith(
            publicResolver(), executor(2), defaultHttpClient(publicResolver()),
            (address, timeoutMillis) -> false,
            (address, port, timeoutMillis) -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    return true;
                } catch (InterruptedException cancelled) {
                    interrupted.countDown();
                    throw new IOException("cancelled", cancelled);
                }
            },
            MonitorDeadline::after);
        Thread caller = new Thread(() -> {
            try {
                service.checkPorts("example.com", List.of(80, 443));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        caller.start();
        assertTrue(started.await(1, TimeUnit.SECONDS), "port probes did not start");
        caller.interrupt();
        caller.join(1_000);

        assertFalse(caller.isAlive(), "interrupted caller did not return");
        assertTrue(failure.get() instanceof IOException);
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
        List<MonitorDeadline> deadlines = new ArrayList<>();
        BoundHttpClient client = new BoundHttpClient(resolver, (uri, method, address, deadline) -> {
            schemes.add(uri.getScheme());
            deadlines.add(deadline);
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
        assertSame(deadlines.get(0), deadlines.get(1));
        assertTrue(result.httpReachable());
        assertEquals(204, result.httpStatus());
    }

    @Test
    void privateRedirectPolicyRejectionIsPropagatedBeforeHttpFallback() throws Exception {
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

        assertThrows(MonitorTargetPolicy.BlockedTargetException.class,
            () -> service.ping("example.com"));

        assertEquals(0, privateConnections.get());
    }

    @Test
    void completeNetworkProbeApplicationContextStartsWithoutAmbiguousExecutors() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(MonitorResolverConfiguration.class, NetworkToolProbeConfiguration.class,
                MonitorAddressResolver.class, BoundHttpClient.class, SafeNetworkProbeService.class,
                PingTestController.class, PortCheckerController.class);
            context.getBeanFactory().registerSingleton(
                "queryHistoryRecorder", org.mockito.Mockito.mock(QueryHistoryRecorder.class));
            context.refresh();

            assertNotNull(context.getBean(SafeNetworkProbeService.class));
            assertNotNull(context.getBean(BoundHttpClient.class));
            assertNotNull(context.getBean(PingTestController.class));
            assertNotNull(context.getBean(PortCheckerController.class));
            ThreadPoolTaskExecutor portExecutor = context.getBean(
                NetworkToolProbeConfiguration.PORT_EXECUTOR_BEAN, ThreadPoolTaskExecutor.class);
            assertEquals("network-tool-probe-", portExecutor.getThreadNamePrefix());
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
