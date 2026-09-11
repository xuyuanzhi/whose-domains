package info.wesite.admin.blog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.wesite.core.blog.BlogEditorialException;

/** Admin-only client: finite timeouts, no response bodies or secrets in errors. */
@Component
public class BlogAiClient {
    @Value("${ai.deepseek.api-key:${DEEPSEEK_API_KEY:}}") private String apiKey;
    @Value("${ai.deepseek.base-url:https://api.deepseek.com/v1}") private String baseUrl;
    @Value("${ai.deepseek.model:deepseek-chat}") private String model;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    public String complete(String system, String user) throws Exception {
        if (!isConfigured()) throw new BlogEditorialException("请为 Admin 配置 DEEPSEEK_API_KEY 后再使用一键优化。");
        URI endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getUserInfo() != null) {
            throw new BlogEditorialException("AI 服务地址必须使用 HTTPS。");
        }
        String body = json.writeValueAsString(Map.of("model", model, "max_tokens", 8192,
            "response_format", Map.of("type", "json_object"), "messages", List.of(
                Map.of("role", "system", "content", system), Map.of("role", "user", "content", user))));
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(150))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var pending = http.sendAsync(request, info -> new LimitedBody());
        try {
            var response = pending.get(150, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new BlogEditorialException("AI 服务请求失败（HTTP " + response.statusCode() + "），请检查额度和配置后重试。");
            byte[] bytes = response.body();
            var choice = json.readTree(bytes).path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) throw new BlogEditorialException("AI 未完整生成文章，请缩小优化范围后重试。");
            String content = choice.path("message").path("content").asText("");
            if (content.isBlank()) throw new BlogEditorialException("AI 未返回有效内容。");
            return content;
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
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
                    body.completeExceptionally(new BlogEditorialException("AI 返回内容过大，请缩短文章后重试。"));
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
