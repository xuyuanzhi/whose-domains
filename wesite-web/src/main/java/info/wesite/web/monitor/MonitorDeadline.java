package info.wesite.web.monitor;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** A monotonic, shared deadline that is never reset between probe steps. */
public final class MonitorDeadline {

    private final long deadlineNanos;
    private final LongSupplier ticker;

    private MonitorDeadline(long deadlineNanos, LongSupplier ticker) {
        this.deadlineNanos = deadlineNanos;
        this.ticker = ticker;
    }

    public static MonitorDeadline after(Duration duration) {
        return create(duration, System::nanoTime);
    }

    static MonitorDeadline forTest(Duration duration, LongSupplier ticker) {
        return create(duration, ticker);
    }

    private static MonitorDeadline create(Duration duration, LongSupplier ticker) {
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(ticker, "ticker");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("deadline duration must be positive");
        }
        long now = ticker.getAsLong();
        long durationNanos;
        try {
            durationNanos = duration.toNanos();
        } catch (ArithmeticException overflow) {
            durationNanos = Long.MAX_VALUE;
        }
        long deadline = durationNanos >= Long.MAX_VALUE - now
            ? Long.MAX_VALUE
            : now + durationNanos;
        return new MonitorDeadline(deadline, ticker);
    }

    public MonitorDeadline bounded(Duration maximum) throws SocketTimeoutException {
        throwIfExpired();
        long now = ticker.getAsLong();
        long maximumNanos = maximum.toNanos();
        long candidate = maximumNanos >= Long.MAX_VALUE - now
            ? Long.MAX_VALUE
            : now + maximumNanos;
        return new MonitorDeadline(Math.min(deadlineNanos, candidate), ticker);
    }

    public void throwIfExpired() throws SocketTimeoutException {
        remainingNanos();
    }

    public int timeoutMillis(int maximumMillis) throws SocketTimeoutException {
        if (maximumMillis <= 0) {
            throw new IllegalArgumentException("maximumMillis must be positive");
        }
        long remaining = remainingNanos();
        long millis = Math.max(1L, (remaining + 999_999L) / 1_000_000L);
        return (int) Math.min(maximumMillis, millis);
    }

    long remainingNanos() throws SocketTimeoutException {
        long remaining = deadlineNanos - ticker.getAsLong();
        if (remaining <= 0) {
            throw new SocketTimeoutException("Monitoring deadline exceeded");
        }
        return remaining;
    }
}
