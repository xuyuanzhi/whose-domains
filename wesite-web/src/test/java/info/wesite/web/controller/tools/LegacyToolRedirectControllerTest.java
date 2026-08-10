package info.wesite.web.controller.tools;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacyToolRedirectControllerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new LegacyToolRedirectController())
            .build();

    @ParameterizedTest
    @CsvSource({
            "/tools/domain_analyzer,/tools/domain-analyzer",
            "/tools/dns_analyzer,/tools/dns-analyzer",
            "/tools/ssl_checker,/tools/ssl-checker",
            "/tools/competitor_analysis,/tools/competitor-analysis"
    })
    void permanentlyRedirectsLegacyToolPaths(String legacy, String canonical) throws Exception {
        mvc.perform(get(legacy))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", canonical));
        mvc.perform(head(legacy))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", canonical));
    }
}
