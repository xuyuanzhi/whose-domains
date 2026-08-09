package info.wesite.web.monitor;

import java.util.Objects;

/**
 * A source-specific monitoring result. Failed results deliberately carry no
 * state so callers cannot accidentally merge a timeout or parse error as an
 * observed empty value.
 */
public record MonitorCollectorResult(
    Source source,
    boolean successful,
    MonitorState state,
    FailureKind failureKind,
    String failureMessage) {

    public MonitorCollectorResult {
        Objects.requireNonNull(source, "source");
        if (successful) {
            Objects.requireNonNull(state, "successful collector state");
            failureKind = null;
            failureMessage = null;
        } else {
            state = null;
            Objects.requireNonNull(failureKind, "failed collector kind");
            failureMessage = failureMessage == null ? "" : failureMessage.trim();
        }
    }

    public static MonitorCollectorResult success(Source source, MonitorState state) {
        return new MonitorCollectorResult(source, true, state, null, null);
    }

    public static MonitorCollectorResult failure(
        Source source,
        FailureKind failureKind,
        String failureMessage) {
        return new MonitorCollectorResult(source, false, null, failureKind, failureMessage);
    }

    public enum Source {
        DOMAIN,
        DNS,
        SSL,
        WEBSITE
    }

    public enum FailureKind {
        TIMEOUT,
        NOT_FOUND,
        PARSE_ERROR,
        BLOCKED_TARGET,
        LOOKUP_ERROR,
        NO_SOURCE
    }
}
