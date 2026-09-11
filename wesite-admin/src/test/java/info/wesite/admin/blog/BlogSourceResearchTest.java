package info.wesite.admin.blog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import info.wesite.core.blog.BlogEditCommand;

class BlogSourceResearchTest {
    BlogAiClient ai = mock(BlogAiClient.class);
    BlogSourceResearch research = spy(new BlogSourceResearch(ai));
    BlogEditCommand article = new BlogEditCommand("p", "dns", "DNS TTL", "Summary", "<p>DNS cache</p>", "a", "dns", "dns", "m", "d");
    @Test void discoversAndReadsSourcesWithoutUserSuppliedLinks() throws Exception {
        String url = "https://www.rfc-editor.org/rfc/rfc1035.html";
        when(ai.complete(anyString(), anyString())).thenReturn("{\"urls\":[\"" + url + "\"]}");
        doReturn(new BlogSourceResearch.Source(url, "DNS", "TTL controls caching")).when(research).fetch(url);
        var result = research.research(article, "");
        assertEquals(url, result.sources().get(0).url());
        verify(research).fetch(url);
    }
    @Test void extractsTopicEvidenceBeyondLongDocumentIntroduction() {
        String text = "Introduction ".repeat(2000) + "TTL controls caching. ".repeat(100);
        String excerpt = BlogSourceResearch.excerpt(text, "DNS TTL");
        assertTrue(excerpt.contains("TTL controls caching"));
        assertTrue(excerpt.startsWith("Introduction"));
        assertTrue(excerpt.length() <= 16000);
    }
    @Test void oldBrokenLinksCannotConsumeDiscoveredSourceBudget() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 6; i++) body.append("<a href=\"https://www.iana.org/missing/").append(i).append("\">Old</a>");
        var input = new BlogEditCommand("p", "dns", "DNS TTL", "Summary", body.toString(), "a", "dns", "dns", "m", "d");
        String url = "https://www.rfc-editor.org/rfc/rfc1035.html";
        when(ai.complete(anyString(), anyString())).thenReturn("{\"urls\":[\"" + url + "\"]}");
        doThrow(new java.io.IOException()).when(research).fetch(anyString());
        doReturn(new BlogSourceResearch.Source(url, "DNS", "TTL controls caching")).when(research).fetch(url);
        assertTrue(research.research(input, "").sources().stream().anyMatch(source -> source.url().equals(url)));
        verify(research, atMost(6)).fetch(anyString());
    }
    @Test void plainTextResponsePreservesParametersAndLineBreaks() throws Exception {
        String plain = "dig <domain> +short\nTTL <ttl> controls caching.\n".repeat(4);
        var http = mock(java.net.http.HttpClient.class);
        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<byte[]> response = mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(
            java.util.Map.of("content-type", List.of("text/plain; charset=UTF-8")), (a, b) -> true));
        when(response.body()).thenReturn(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(http.sendAsync(any(java.net.http.HttpRequest.class), org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<byte[]>>any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(response));
        var field = BlogSourceResearch.class.getDeclaredField("http");
        field.setAccessible(true); field.set(research, http);
        assertEquals(plain, research.fetch("https://www.rfc-editor.org/rfc/rfc1035.txt").text());
    }
    @Test void rejectsPrivateUrlsCredentialsPortsAndLookalikeHosts() {
        for (String url : List.of("http://www.rfc-editor.org/doc", "https://127.0.0.1/", "https://localhost/",
            "https://www.rfc-editor.org.evil.example/", "https://user@www.rfc-editor.org/", "https://www.rfc-editor.org:8443/"))
            assertFalse(BlogSourceResearch.allowed(url), url);
        assertTrue(BlogSourceResearch.allowed("https://developer.mozilla.org/en-US/docs/Web"));
    }
    @Test void failedFetchIsNotPresentedAsEvidence() throws Exception {
        String url = "https://www.rfc-editor.org/missing";
        when(ai.complete(anyString(), anyString())).thenReturn("{\"urls\":[\"" + url + "\"]}");
        doThrow(new java.io.IOException()).when(research).fetch(url);
        var result = research.research(article, "");
        assertTrue(result.sources().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(s -> s.contains(url)));
    }
    @Test void candidateFailureCanStillReadProvidedOfficialLink() throws Exception {
        when(ai.complete(anyString(), anyString())).thenThrow(new java.io.IOException());
        String url = "https://www.iana.org/domains";
        doReturn(new BlogSourceResearch.Source(url, "Domains", "Evidence")).when(research).fetch(url);
        assertEquals(1, research.research(article, url).sources().size());
    }
}
