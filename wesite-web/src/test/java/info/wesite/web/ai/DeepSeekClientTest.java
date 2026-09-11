package info.wesite.web.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class DeepSeekClientTest {
    private final DeepSeekClient client = new DeepSeekClient();
    @Test void rejectsTruncatedMissingAndEmptyResponses() {
        for (String value : new String[]{"{}", "null", "{\"choices\":[]}",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}"}) {
            assertThrows(IOException.class, () -> client.parseResponse(value));
        }
    }
    @Test void acceptsOnlyCompleteText() throws Exception {
        assertEquals("Complete", client.parseResponse("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Complete\"}}]}"));
    }
    @Test void oversizedBodyCancelsSubscriptionBeforeBuffering() {
        var body = new DeepSeekClient.LimitedBody();
        var subscription = org.mockito.Mockito.mock(java.util.concurrent.Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(java.util.List.of(java.nio.ByteBuffer.allocate(512 * 1024 + 1)));
        org.mockito.Mockito.verify(subscription).cancel();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
