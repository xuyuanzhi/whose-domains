package info.wesite.core.diagnostics;

public record DiagnosticEvent(String key, String source, String route, String method, int status,
        String exceptionType, String frames, String requestId, long time, boolean correlationOnly) {
}
