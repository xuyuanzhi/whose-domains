package info.wesite.web.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void recordsZeroMillisecondSuccessfulProbeAsNotAvailableHistory() throws Exception {
        when(probes.ping("fast.example")).thenReturn(new PingProbeResult(
                "fast.example", "93.184.216.34", true, 0,
                false, null, null, true, 0L, "Excellent"));

        mockMvc.perform(pingRequest("fast.example", "198.18.10.6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avgResponseMs").value(0));

        verify(queryHistoryRecorder).recordAsync(
                isNull(), eq(UserQueryHistory.TYPE_PING), eq("fast.example"), eq("Online — N/A"));
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
