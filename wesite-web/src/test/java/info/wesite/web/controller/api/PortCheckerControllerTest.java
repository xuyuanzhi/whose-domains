package info.wesite.web.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import info.wesite.core.entity.UserQueryHistory;
import info.wesite.web.monitor.SafeNetworkProbeService;
import info.wesite.web.monitor.SafeNetworkProbeService.PortProbeResult;
import info.wesite.web.monitor.SafeNetworkProbeService.PortResult;

class PortCheckerControllerTest {

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
    void delegatesValidatedPortsAndPreservesResponseOrder() throws Exception {
        when(probes.checkPorts("example.com", List.of(443, 80))).thenReturn(new PortProbeResult(
                "normalized.example", List.of(new PortResult(443, true, 12), new PortResult(80, false, 30))));

        mockMvc.perform(portRequest("{\"host\":\"example.com\",\"ports\":[443,80]}", "198.18.20.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.host").value("normalized.example"))
                .andExpect(jsonPath("$.data.ports[0].port").value(443))
                .andExpect(jsonPath("$.data.ports[0].service").value("HTTPS"))
                .andExpect(jsonPath("$.data.ports[0].open").value(true))
                .andExpect(jsonPath("$.data.ports[0].responseMs").value(12))
                .andExpect(jsonPath("$.data.ports[1].port").value(80))
                .andExpect(jsonPath("$.data.ports[1].service").value("HTTP"))
                .andExpect(jsonPath("$.data.ports[1].open").value(false))
                .andExpect(jsonPath("$.data.ports[1].responseMs").value(30))
                .andExpect(jsonPath("$.data.openCount").value(1))
                .andExpect(jsonPath("$.data.closedCount").value(1));

        verify(probes).checkPorts("example.com", List.of(443, 80));
        verify(queryHistoryRecorder).recordAsync(
                isNull(), eq(UserQueryHistory.TYPE_PORT), eq("normalized.example"), eq("1/2 ports open"));
    }

    @Test
    void rejectsMoreThanTwentyPortsBeforeInvokingTheService() throws Exception {
        String ports = String.join(",", java.util.Collections.nCopies(21, "443"));

        mockMvc.perform(portRequest("{\"host\":\"example.com\",\"ports\":[" + ports + "]}", "198.18.20.2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        verify(probes, never()).checkPorts(anyString(), anyList());
    }

    @Test
    void rejectsOutOfRangePortsBeforeInvokingTheService() throws Exception {
        for (String body : List.of(
                "{\"host\":\"example.com\",\"ports\":[0]}",
                "{\"host\":\"example.com\",\"ports\":[65536]}")) {
            mockMvc.perform(portRequest(body, "198.18.20.3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }

        verify(probes, never()).checkPorts(anyString(), anyList());
    }

    @Test
    void rejectsNullPortEntriesBeforeInvokingTheService() throws Exception {
        mockMvc.perform(portRequest("{\"host\":\"example.com\",\"ports\":[443,null]}", "198.18.20.4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        verify(probes, never()).checkPorts(anyString(), anyList());
    }

    @Test
    void rejectsAbsentAndEmptyPortListsBeforeInvokingTheService() throws Exception {
        for (String body : List.of("{\"host\":\"example.com\"}", "{\"host\":\"example.com\",\"ports\":[]}")) {
            mockMvc.perform(portRequest(body, "198.18.20.5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.msg").value("At least one port is required."));
        }

        verify(probes, never()).checkPorts(anyString(), anyList());
    }

    @Test
    void rejectsAbsentRequestAndBlankHostsBeforeInvokingTheService() throws Exception {
        for (String body : List.of("null", "{\"host\":\"   \",\"ports\":[443]}")) {
            mockMvc.perform(portRequest(body, "198.18.20.6"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.msg").value("Host is required."));
        }

        verify(probes, never()).checkPorts(anyString(), anyList());
    }

    @Test
    void hidesRawProbeFailureDetails() throws Exception {
        when(probes.checkPorts("example.com", List.of(443)))
                .thenThrow(new IOException("resolver implementation detail"));

        mockMvc.perform(portRequest("{\"host\":\"example.com\",\"ports\":[443]}", "198.18.20.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("Unable to check ports for this host."))
                .andExpect(content().string(not(containsString("resolver implementation detail"))));

        verify(queryHistoryRecorder, never()).recordAsync(
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
    }

    @Test
    void mapsFacadeValidationFailuresToAStableMessage() throws Exception {
        when(probes.checkPorts("invalid.example", List.of(443)))
                .thenThrow(new IllegalArgumentException("host parser detail"));

        mockMvc.perform(portRequest("{\"host\":\"invalid.example\",\"ports\":[443]}", "198.18.20.8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("Invalid port request."))
                .andExpect(content().string(not(containsString("host parser detail"))));

        verify(queryHistoryRecorder, never()).recordAsync(
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
    }

    @Test
    void preservesTheExistingRateLimitFlow() throws Exception {
        when(probes.checkPorts("example.com", List.of(443))).thenReturn(successfulProbe());

        for (int request = 0; request < 5; request++) {
            mockMvc.perform(portRequest("{\"host\":\"example.com\",\"ports\":[443]}", "198.18.20.9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        mockMvc.perform(portRequest("{\"host\":\"example.com\",\"ports\":[443]}", "198.18.20.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("Too many requests. Please try again later."));

        verify(probes, times(5)).checkPorts("example.com", List.of(443));
    }

    private PortCheckerController newController() {
        return new PortCheckerController(probes, queryHistoryRecorder);
    }

    private static PortProbeResult successfulProbe() {
        return new PortProbeResult("example.com", List.of(new PortResult(443, true, 12)));
    }

    private static MockHttpServletRequestBuilder portRequest(String body, String remoteAddress) {
        return post("/api/tools/port-check")
                .contentType(APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                });
    }
}
