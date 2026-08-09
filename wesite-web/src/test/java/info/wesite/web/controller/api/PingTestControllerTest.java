package info.wesite.web.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.stream.Collectors;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import info.wesite.core.entity.UserQueryHistory;
import info.wesite.web.monitor.SafeNetworkProbeService;
import info.wesite.web.monitor.SafeNetworkProbeService.PingProbeResult;

class PingTestControllerTest {

    private MockMvc mockMvc;

    private SafeNetworkProbeService probes;

    private QueryHistoryRecorder queryHistoryRecorder;

    @BeforeEach
    void setUp() {
        probes = org.mockito.Mockito.mock(SafeNetworkProbeService.class);
        queryHistoryRecorder = org.mockito.Mockito.mock(QueryHistoryRecorder.class);
        mockMvc = MockMvcBuilders.standaloneSetup(newController()).build();
    }

    @Test
    void returnsProbeFieldsFromSafeService() throws Exception {
        when(probes.ping("example.com")).thenReturn(successfulProbe());

        mockMvc.perform(pingRequest("example.com", "198.18.10.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.host").value("example.com"))
                .andExpect(jsonPath("$.data.resolvedIp").value("93.184.216.34"))
                .andExpect(jsonPath("$.data.icmpReachable").value(false))
                .andExpect(jsonPath("$.data.icmpResponseMs").value(5))
                .andExpect(jsonPath("$.data.httpReachable").value(true))
                .andExpect(jsonPath("$.data.httpStatus").value(200))
                .andExpect(jsonPath("$.data.httpResponseMs").value(40))
                .andExpect(jsonPath("$.data.online").value(true))
                .andExpect(jsonPath("$.data.avgResponseMs").value(40))
                .andExpect(jsonPath("$.data.speed").value("Excellent"));

        verify(probes).ping("example.com");
        verify(queryHistoryRecorder).recordAsync(
                isNull(), eq(UserQueryHistory.TYPE_PING), eq("example.com"), eq("Online — 40ms"));
    }

    @Test
    void preservesNullAndNotAvailableResponseSemanticsForZeroMillisecondSuccess() throws Exception {
        when(probes.ping("fast.example")).thenReturn(new PingProbeResult(
                "fast.example", "93.184.216.34", false, 5,
                true, 200, 0L, true, 0L, "Excellent"));

        mockMvc.perform(pingRequest("fast.example", "198.18.10.6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.httpResponseMs").value(nullValue()))
                .andExpect(jsonPath("$.data.avgResponseMs").value(nullValue()))
                .andExpect(jsonPath("$.data.speed").value("N/A"));

        verify(queryHistoryRecorder).recordAsync(
                isNull(), eq(UserQueryHistory.TYPE_PING), eq("fast.example"), eq("Online — N/A"));
        verify(probes).ping("fast.example");
    }

    @Test
    void sanitizesBlockedTargetFailure() throws Exception {
        when(probes.ping(anyString())).thenThrow(new IOException("internal resolver detail"));

        mockMvc.perform(pingRequest("blocked.example", "198.18.10.2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("Unable to probe this host."))
                .andExpect(content().string(not(containsString("internal resolver detail"))));

        verify(queryHistoryRecorder, never()).recordAsync(
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
        verify(probes).ping("blocked.example");
    }

    @Test
    void rejectsInvalidHostsWithoutLeakingFacadeDetails() throws Exception {
        when(probes.ping("invalid.example")).thenThrow(new IllegalArgumentException("host parsing detail"));

        mockMvc.perform(pingRequest("invalid.example", "198.18.10.3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("Invalid host."))
                .andExpect(content().string(not(containsString("host parsing detail"))));

        verify(queryHistoryRecorder, never()).recordAsync(
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
        verify(probes).ping("invalid.example");
    }

    @Test
    void failureLogsNeverIncludeUntrustedHostText() throws Exception {
        String rawHost = "user:secret@example.com\r\nFORGED log line";
        when(probes.ping(rawHost)).thenThrow(new IOException("transport failed for " + rawHost));
        ch.qos.logback.classic.Logger controllerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PingTestController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
        try {
            PingTestController.PingRequest request = new PingTestController.PingRequest();
            request.setHost(rawHost);
            MockHttpServletRequest httpRequest = new MockHttpServletRequest();
            httpRequest.setRemoteAddr("198.18.10.7");

            newController().ping(request, httpRequest);
        } finally {
            controllerLogger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream()
                .map(event -> event.getFormattedMessage() + "\n"
                        + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
        org.junit.jupiter.api.Assertions.assertFalse(logs.contains(rawHost));
        org.junit.jupiter.api.Assertions.assertFalse(logs.contains("user:secret"));
        org.junit.jupiter.api.Assertions.assertFalse(logs.contains("FORGED log line"));
        verify(probes).ping(rawHost);
    }

    @Test
    void rejectsBlankHostsWithoutInvokingTheService() throws Exception {
        mockMvc.perform(pingRequest("   ", "198.18.10.4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("Host is required."));

        verify(probes, never()).ping(anyString());
        verify(queryHistoryRecorder, never()).recordAsync(
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
    }

    @Test
    void rejectsRequestsAfterTheExistingRateLimitIsReached() throws Exception {
        when(probes.ping("example.com")).thenReturn(successfulProbe());

        for (int request = 0; request < 5; request++) {
            mockMvc.perform(pingRequest("example.com", "198.18.10.5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        mockMvc.perform(pingRequest("example.com", "198.18.10.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("Too many requests. Please try again later."));

        verify(probes, times(5)).ping("example.com");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pingRequest(
            String host, String remoteAddress) {
        return post("/api/tools/ping")
                .contentType(APPLICATION_JSON)
                .content("{\"host\":\"" + host + "\"}")
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                });
    }

    private static PingProbeResult successfulProbe() {
        return new PingProbeResult(
                "example.com", "93.184.216.34", false, 5,
                true, 200, 40L, true, 40L, "Excellent");
    }

    private PingTestController newController() {
        return new PingTestController(probes, queryHistoryRecorder);
    }
}
