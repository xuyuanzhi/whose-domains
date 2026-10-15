package info.wesite.web.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import io.modelcontextprotocol.common.McpTransportContext;

class McpRequestContextExtractorTest {

    private static JwtDecoder decoderFor(String subject) {
        return token -> Jwt.withTokenValue(token).header("alg", "none").subject(subject).build();
    }

    @Test
    void bearerTokenYieldsTheAuthenticatedUserId() {
        McpRequestContextExtractor extractor = new McpRequestContextExtractor(decoderFor("user-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc.def.ghi");

        McpTransportContext context = extractor.extract(request);

        assertEquals("user-1", context.get("userId"));
    }

    @Test
    void missingAuthorizationHeaderYieldsEmptyContext() {
        McpRequestContextExtractor extractor = new McpRequestContextExtractor(decoderFor("user-1"));

        assertEquals(McpTransportContext.EMPTY, extractor.extract(new MockHttpServletRequest()));
    }

    @Test
    void invalidTokenIsIgnoredRatherThanTrusted() {
        JwtDecoder failing = token -> {
            throw new JwtException("bad token");
        };
        McpRequestContextExtractor extractor = new McpRequestContextExtractor(failing);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad");

        assertEquals(McpTransportContext.EMPTY, extractor.extract(request));
    }
}
