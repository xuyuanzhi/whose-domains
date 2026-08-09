package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class MonitorAddressResolverTest {

    @Test
    void blockingResolutionIsCancelledAndReturnsWithinTheDeadline() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 0);
        CountDownLatch interrupted = new CountDownLatch(1);
        MonitorAddressResolver resolver = new MonitorAddressResolver(executor, host -> {
            try {
                new CountDownLatch(1).await();
                return new InetAddress[0];
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw new IOException("cancelled", cancelled);
            }
        });

        long startedAt = System.nanoTime();
        try {
            assertThrows(SocketTimeoutException.class, () -> resolver.resolve(
                "example.com", MonitorDeadline.after(Duration.ofMillis(100))));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            assertTrue(elapsedMillis < 1_000, "resolver exceeded bounded wall time");
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "timed out lookup was not cancelled");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void uninterruptibleLookupOccupiesOnlyTheFixedCapacityAndRejectsOverflow() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 0);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean();
        MonitorAddressResolver resolver = new MonitorAddressResolver(executor, host -> {
            started.countDown();
            while (!released.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // Model InetAddress.getAllByName, which may not react to cancellation.
                }
            }
            return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        });

        try {
            assertThrows(SocketTimeoutException.class, () -> resolver.resolve(
                "first.example", MonitorDeadline.after(Duration.ofMillis(100))));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            IOException saturated = assertThrows(IOException.class, () -> resolver.resolve(
                "second.example", MonitorDeadline.after(Duration.ofSeconds(1))));
            assertTrue(saturated.getMessage().contains("capacity"));
        } finally {
            released.set(true);
            executor.shutdown();
        }
    }

    @Test
    void resultCompletedAfterTheMonotonicDeadlineIsStillRejected() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 0);
        AtomicLong now = new AtomicLong();
        MonitorAddressResolver resolver = new MonitorAddressResolver(executor, host -> {
            now.addAndGet(Duration.ofMillis(101).toNanos());
            return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        });

        try {
            assertThrows(SocketTimeoutException.class, () -> resolver.resolve(
                "example.com",
                MonitorDeadline.forTest(Duration.ofMillis(100), now::get)));
        } finally {
            executor.shutdown();
        }
    }

    private static ThreadPoolTaskExecutor executor(int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("monitor-resolver-test-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
