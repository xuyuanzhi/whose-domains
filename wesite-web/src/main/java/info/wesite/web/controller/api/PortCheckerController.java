package info.wesite.web.controller.api;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import info.wesite.core.entity.UserQueryHistory;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 端口检测工具 API
 */
@Tag(name = "Port Checker API")
@RestController
@RequestMapping("/api/tools")
public class PortCheckerController {

    private static final Logger log = LoggerFactory.getLogger(PortCheckerController.class);
    private static final int MAX_PORTS = 20;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    // Private / loopback CIDR prefixes to block
    private static final List<String> BLOCKED_PREFIXES = Arrays.asList(
        "127.", "10.", "192.168.", "169.254.", "0.", "::1", "fc", "fd"
    );

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @Autowired
    private QueryHistoryRecorder queryHistoryRecorder;

    @Operation(summary = "检测主机端口状态")
    @PostMapping("/port-check")
    public ResponseJson<Map<String, Object>> checkPorts(
            @RequestBody PortCheckRequest request,
            HttpServletRequest httpRequest) {

        String ip = IpUtils.getRequestIp(httpRequest);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        String host = request.getHost();
        List<Integer> ports = request.getPorts();

        if (StringUtils.isBlank(host)) return ResponseJson.failure("Host is required.");
        if (ports == null || ports.isEmpty()) return ResponseJson.failure("At least one port is required.");

        host = host.toLowerCase().trim();
        if (host.startsWith("https://")) host = host.substring(8);
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.contains("/")) host = host.substring(0, host.indexOf('/'));

        // Resolve and validate target IP (block private ranges)
        try {
            InetAddress addr = InetAddress.getByName(host);
            String resolvedIp = addr.getHostAddress();
            for (String prefix : BLOCKED_PREFIXES) {
                if (resolvedIp.startsWith(prefix)) {
                    return ResponseJson.failure("Private/loopback addresses are not allowed.");
                }
            }
        } catch (Exception e) {
            return ResponseJson.failure("Cannot resolve host: " + host);
        }

        // Limit ports
        if (ports.size() > MAX_PORTS) {
            ports = ports.subList(0, MAX_PORTS);
        }

        final String finalHost = host;
        try {
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (int port : ports) {
                final int p = port;
                futures.add(CompletableFuture.supplyAsync(() -> checkPort(finalHost, p), executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);

            List<Map<String, Object>> results = new ArrayList<>();
            int openCount = 0;
            for (CompletableFuture<Map<String, Object>> f : futures) {
                Map<String, Object> r = f.get();
                results.add(r);
                if (Boolean.TRUE.equals(r.get("open"))) openCount++;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("host", finalHost);
            data.put("ports", results);
            data.put("openCount", openCount);
            data.put("closedCount", results.size() - openCount);

            RateLimitUtils.incrementRequestCount(ip);
            queryHistoryRecorder.recordAsync(UserQueryHistory.TYPE_PORT, finalHost,
                openCount + "/" + results.size() + " ports open");
            return ResponseJson.success(data);

        } catch (Exception e) {
            log.error("Port check error for {}", host, e);
            return ResponseJson.failure("Port check failed: " + e.getMessage());
        }
    }

    private Map<String, Object> checkPort(String host, int port) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("port", port);
        result.put("service", getServiceName(port));
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            result.put("open", true);
            result.put("responseMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("open", false);
            result.put("responseMs", System.currentTimeMillis() - start);
        }
        return result;
    }

    private String getServiceName(int port) {
        switch (port) {
            case 21: return "FTP";      case 22: return "SSH";
            case 23: return "Telnet";   case 25: return "SMTP";
            case 53: return "DNS";      case 80: return "HTTP";
            case 110: return "POP3";    case 143: return "IMAP";
            case 443: return "HTTPS";   case 465: return "SMTPS";
            case 587: return "SMTP/TLS";case 993: return "IMAPS";
            case 995: return "POP3S";   case 1433: return "MSSQL";
            case 1521: return "Oracle"; case 3306: return "MySQL";
            case 3389: return "RDP";    case 5432: return "PostgreSQL";
            case 6379: return "Redis";  case 8080: return "HTTP-Alt";
            case 8443: return "HTTPS-Alt"; case 27017: return "MongoDB";
            default: return "Unknown";
        }
    }

    public static class PortCheckRequest {
        private String host;
        private List<Integer> ports;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public List<Integer> getPorts() { return ports; }
        public void setPorts(List<Integer> ports) { this.ports = ports; }
    }
}
