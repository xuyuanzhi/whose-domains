package info.wesite.core.diagnostics;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DiagnosticIngestTest {
    private MockMvc mvc; private DiagnosticRecorder recorder;
    private final String event="{\"id\":\""+UUID.randomUUID()+"\",\"kind\":\"client_error\",\"code\":\"TypeError\",\"route\":\"/\",\"script\":\"/static/js/common.js\"}";
    @BeforeEach void setup() {
        var config=new DiagnosticsProperties();config.setEnabled(true);recorder=mock(DiagnosticRecorder.class);when(recorder.submit(any())).thenReturn(true);
        var catalog=mock(DiagnosticCatalog.class);when(catalog.routes()).thenReturn(Set.of("/","unmatched"));when(catalog.script("/static/js/common.js")).thenReturn(true);
        mvc=MockMvcBuilders.standaloneSetup(new DiagnosticIngestController(config,recorder,catalog,new ObjectMapper())).build();
    }
    private ResultActions submit(String body) throws Exception {return mvc.perform(post("/diagnostics/events").with(req->{req.setRemoteAddr(UUID.randomUUID().toString());return req;}).header("Origin","http://localhost").contentType("application/json").content(body));}
    @Test void acceptsSafeFieldsWithoutAuthentication() throws Exception {submit("{\"events\":["+event+"]}").andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));verify(recorder).submit(any());}
    @Test void rejectsSensitiveFieldsAndUnknownScriptOrRoute() throws Exception {
        for(String invalid:List.of(event.replace("\"kind\"","\"password\":\"secret\",\"kind\""),event.replace("/static/js/common.js","/private/token.js"),event.replace("\"route\":\"/\"","\"route\":\"/domain/private.example?token=secret\""))) submit("{\"events\":["+invalid+"]}").andExpect(status().isBadRequest());
        verifyNoInteractions(recorder);
    }
    @Test void rejectsCrossOriginAndOversizedRequests() throws Exception {
        mvc.perform(post("/diagnostics/events").header("Origin","https://attacker.example").contentType("application/json").content("{\"events\":["+event+"]}")).andExpect(status().isForbidden());
        submit("x".repeat(16385)).andExpect(status().isPayloadTooLarge());verifyNoInteractions(recorder);
    }
    @Test void validatesWholeBatchBeforeEnqueue() throws Exception {submit("{\"events\":["+event+",{}]}").andExpect(status().isBadRequest());verifyNoInteractions(recorder);}
    @Test void rateLimitsEventCountAcrossBatches() throws Exception {
        String ip=UUID.randomUUID().toString();
        String body="{\"events\":["+String.join(",",Collections.nCopies(10,event))+"]}";
        for(int i=0;i<7;i++) mvc.perform(post("/diagnostics/events").with(req->{req.setRemoteAddr(ip);return req;})
            .header("Origin","http://localhost").contentType("application/json").content(body))
            .andExpect(status().is(i<6?200:429));
        verify(recorder,times(60)).submit(any());
    }

}
