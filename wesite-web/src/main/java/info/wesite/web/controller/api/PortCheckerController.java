package info.wesite.web.controller.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import info.wesite.web.monitor.SafeNetworkProbeService.PortProbeResult;
import info.wesite.web.monitor.SafeNetworkProbeService.PortResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/** Port checker API. */
@Tag(name = "Port Checker API")
@RestController
@RequestMapping("/api/tools")
public class PortCheckerController {

    private static final Logger log = LoggerFactory.getLogger(PortCheckerController.class);
    private static final int MAX_PORTS = 20;

    private final SafeNetworkProbeService probes;
    private final QueryHistoryRecorder queryHistoryRecorder;

    public PortCheckerController(SafeNetworkProbeService probes, QueryHistoryRecorder queryHistoryRecorder) {
        this.probes = probes;
        this.queryHistoryRecorder = queryHistoryRecorder;
    }

    @Operation(summary = "Check host port status")
    @PostMapping("/port-check")
    public ResponseJson<Map<String, Object>> checkPorts(
            @RequestBody(required = false) PortCheckRequest request,
            HttpServletRequest httpRequest) {

        String ip = IpUtils.getRequestIp(httpRequest);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        if (request == null || StringUtils.isBlank(request.getHost())) {
            return ResponseJson.failure("Host is required.");
        }
        List<Integer> ports = request.getPorts();
        if (ports == null || ports.isEmpty()) {
            return ResponseJson.failure("At least one port is required.");
        }
        if (!validPorts(ports)) {
            return ResponseJson.failure("Invalid port request.");
        }

        try {
            PortProbeResult probe = probes.checkPorts(request.getHost(), ports);
            List<Map<String, Object>> results = new ArrayList<>(probe.ports().size());
            int openCount = 0;
            for (PortResult port : probe.ports()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("port", port.port());
                result.put("service", serviceName(port.port()));
                result.put("open", port.open());
                result.put("responseMs", port.responseMs());
                results.add(result);
                if (port.open()) {
                    openCount++;
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("host", probe.host());
            data.put("ports", results);
            data.put("openCount", openCount);
            data.put("closedCount", results.size() - openCount);

            RateLimitUtils.incrementRequestCount(ip);
            queryHistoryRecorder.recordAsync(QueryHistoryRecorder.currentUserId(), UserQueryHistory.TYPE_PORT, probe.host(),
                    openCount + "/" + results.size() + " ports open");
            return ResponseJson.success(data);
        } catch (IllegalArgumentException invalidRequest) {
            log.warn("Rejected port probe request for {}", request.getHost(), invalidRequest);
            return ResponseJson.failure("Invalid port request.");
        } catch (IOException probeFailure) {
            log.warn("Port probe failed for {}", request.getHost(), probeFailure);
            return ResponseJson.failure("Unable to check ports for this host.");
        }
    }

    private static boolean validPorts(List<Integer> ports) {
        if (ports.size() > MAX_PORTS) {
            return false;
        }
        for (Integer port : ports) {
            if (port == null || port < 1 || port > 65_535) {
                return false;
            }
        }
        return true;
    }

    private static String serviceName(int port) {
        return switch (port) {
            case 21 -> "FTP";
            case 22 -> "SSH";
            case 23 -> "Telnet";
            case 25 -> "SMTP";
            case 53 -> "DNS";
            case 80 -> "HTTP";
            case 110 -> "POP3";
            case 143 -> "IMAP";
            case 443 -> "HTTPS";
            case 465 -> "SMTPS";
            case 587 -> "SMTP/TLS";
            case 993 -> "IMAPS";
            case 995 -> "POP3S";
            case 1433 -> "MSSQL";
            case 1521 -> "Oracle";
            case 3306 -> "MySQL";
            case 3389 -> "RDP";
            case 5432 -> "PostgreSQL";
            case 6379 -> "Redis";
            case 8080 -> "HTTP-Alt";
            case 8443 -> "HTTPS-Alt";
            case 27017 -> "MongoDB";
            default -> "Unknown";
        };
    }

    public static class PortCheckRequest {
        private String host;
        private List<Integer> ports;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public List<Integer> getPorts() {
            return ports;
        }

        public void setPorts(List<Integer> ports) {
            this.ports = ports;
        }
    }
}
