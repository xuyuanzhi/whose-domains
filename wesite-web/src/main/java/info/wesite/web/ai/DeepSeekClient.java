package info.wesite.web.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DeepSeek API 客户端
 * 调用 DeepSeek Chat Completion API 生成内容
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    @Value("${ai.deepseek.api-key:}")
    private String apiKey;

    @Value("${ai.deepseek.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${ai.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${ai.deepseek.max-tokens:4096}")
    private int maxTokens;

    @Value("${ai.deepseek.timeout-seconds:120}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 调用 DeepSeek 生成文本
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 生成的文本内容
     */
    public String chat(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DeepSeek API key is not configured. Set ai.deepseek.api-key or DEEPSEEK_API_KEY env var.");
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String bodyJson = objectMapper.writeValueAsString(body);

        URI endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getUserInfo() != null)
            throw new IOException("AI endpoint must use HTTPS without embedded credentials");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        var pending = httpClient.sendAsync(request, info -> new LimitedBody());
        try {
            var response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new IOException("DeepSeek API error [" + response.statusCode() + "]");
            return parseResponse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("AI request failed, timed out or exceeded response limit");
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    String parseResponse(String body) throws IOException {
        if (body == null || body.length() > 512 * 1024) throw new IOException("AI response missing or oversized");
        JsonNode root;
        try { root = objectMapper.readTree(body); }
        catch (IOException e) { throw new IOException("Invalid AI response JSON"); }
        JsonNode choice = root == null ? null : root.path("choices").path(0);
        if (choice == null || !"stop".equals(choice.path("finish_reason").asText()))
            throw new IOException("AI response incomplete; generation stopped without a complete result");
        JsonNode content = choice.path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) throw new IOException("AI response contains no text");
        return content.asText();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (bytes.size() + chunk.remaining() > 512 * 1024) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("AI response too large"));
                    return;
                }
                byte[] data = new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
