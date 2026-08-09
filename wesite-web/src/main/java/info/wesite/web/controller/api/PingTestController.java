package info.wesite.web.controller.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import info.wesite.core.entity.UserQueryHistory;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.monitor.SafeNetworkProbeService;
import info.wesite.web.monitor.SafeNetworkProbeService.PingProbeResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/** Ping / availability probe API. */
@Tag(name = "Ping Test API")
@RestController
@RequestMapping("/api/tools")
public class PingTestController {

    private static final Logger log = LoggerFactory.getLogger(PingTestController.class);

    private final SafeNetworkProbeService probes;
    private final QueryHistoryRecorder queryHistoryRecorder;

    public PingTestController(SafeNetworkProbeService probes, QueryHistoryRecorder queryHistoryRecorder) {
        this.probes = probes;
        this.queryHistoryRecorder = queryHistoryRecorder;
    }

    @Operation(summary = "Ping / availability probe")
    @PostMapping("/ping")
    public ResponseJson<Map<String, Object>> ping(
            @RequestBody PingRequest request,
            HttpServletRequest httpRequest) {

        String ip = IpUtils.getRequestIp(httpRequest);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        if (StringUtils.isBlank(request.getHost())) {
            return ResponseJson.failure("Host is required.");
        }

        try {
            PingProbeResult probe = probes.ping(request.getHost());
            Map<String, Object> result = probeFields(probe);

            RateLimitUtils.incrementRequestCount(ip);
            queryHistoryRecorder.recordAsync(QueryHistoryRecorder.currentUserId(), UserQueryHistory.TYPE_PING, probe.host(),
                    probe.online() ? "Online — " + (probe.avgResponseMs() != null && probe.avgResponseMs() > 0
                            ? probe.avgResponseMs() + "ms" : "N/A")
                            : "Offline");
            return ResponseJson.success(result);
        } catch (IllegalArgumentException invalidHost) {
            log.warn("Rejected ping probe target [untrusted-target], failureType={}",
                    invalidHost.getClass().getSimpleName());
            return ResponseJson.failure("Invalid host.");
        } catch (IOException probeFailure) {
            log.warn("Ping probe failed for target [untrusted-target], failureType={}",
                    probeFailure.getClass().getSimpleName());
            return ResponseJson.failure("Unable to probe this host.");
        }
    }

    private static Map<String, Object> probeFields(PingProbeResult probe) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", probe.host());
        result.put("resolvedIp", probe.resolvedIp());
        result.put("icmpReachable", probe.icmpReachable());
        result.put("icmpResponseMs", probe.icmpResponseMs());
        result.put("httpReachable", probe.httpReachable());
        result.put("httpStatus", probe.httpStatus());
        result.put("httpResponseMs", positiveLatency(probe.httpResponseMs()));
        result.put("online", probe.online());
        Long average = positiveLatency(probe.avgResponseMs());
        result.put("avgResponseMs", average);
        result.put("speed", average == null ? "N/A" : probe.speed());
        return result;
    }

    private static Long positiveLatency(Long responseMs) {
        return responseMs != null && responseMs > 0 ? responseMs : null;
    }

    public static class PingRequest {
        private String host;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }
    }
}
