package info.wesite.web.monitor;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/** Runs otherwise-unbounded system address resolution on one bounded pool. */
@Component
public final class MonitorAddressResolver implements MonitorTargetPolicy.HostResolver {

    private final AsyncTaskExecutor executor;
    private final BlockingLookup lookup;

    @Autowired
    public MonitorAddressResolver(
        @Qualifier(MonitorResolverConfiguration.EXECUTOR_BEAN) AsyncTaskExecutor executor) {
        this(executor, InetAddress::getAllByName);
    }

    MonitorAddressResolver(AsyncTaskExecutor executor, BlockingLookup lookup) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public InetAddress[] resolve(String host, MonitorDeadline deadline) throws IOException {
        Objects.requireNonNull(deadline, "deadline");
        deadline.throwIfExpired();
        Future<InetAddress[]> future;
        try {
            future = executor.submit(() -> lookup.resolve(host));
        } catch (TaskRejectedException rejected) {
            throw new IOException("Monitor resolver capacity exhausted", rejected);
        }

        long remainingNanos;
        try {
            remainingNanos = deadline.remainingNanos();
        } catch (SocketTimeoutException expired) {
            future.cancel(true);
            throw expired;
        }
        try {
            InetAddress[] addresses = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            deadline.throwIfExpired();
            return addresses;
        } catch (TimeoutException timeout) {
            future.cancel(true);
            SocketTimeoutException failure = new SocketTimeoutException(
                "Monitoring address resolution deadline exceeded");
            failure.initCause(timeout);
            throw failure;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException(
                "Monitoring address resolution interrupted");
            failure.initCause(interrupted);
            throw failure;
        } catch (CancellationException cancelled) {
            throw new IOException("Monitoring address resolution was cancelled", cancelled);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("Monitoring address resolution failed", cause);
        }
    }

    @FunctionalInterface
    interface BlockingLookup {
        InetAddress[] resolve(String host) throws IOException;
    }
}
