package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class BoundHttpClientParsingTest {

    @Test
    void maximumSignedChunkSizeIsRejectedBeforeAllocatingTheChunk() {
        String response = "HTTP/1.1 200 OK\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "\r\n"
            + "7fffffff\r\n";

        IOException failure = assertThrows(IOException.class, () -> parse(response));

        assertTrue(failure.getMessage().contains("size limit"));
    }

    @Test
    void moreThanOneHundredPhysicalHeaderFieldsIsRejected() {
        StringBuilder response = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int index = 0; index < 101; index++) {
            response.append("X-Test-").append(index).append(": value\r\n");
        }
        response.append("Content-Length: 0\r\n\r\n");

        IOException failure = assertThrows(IOException.class, () -> parse(response.toString()));

        assertTrue(failure.getMessage().contains("header"));
    }

    private static BoundHttpClient.Response parse(String rawResponse) throws Throwable {
        Socket socket = mock(Socket.class);
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(
            rawResponse.getBytes(StandardCharsets.ISO_8859_1)));
        Method parser = BoundHttpClient.class.getDeclaredMethod(
            "readResponse", Socket.class, String.class, MonitorDeadline.class);
        parser.setAccessible(true);
        try {
            return (BoundHttpClient.Response) parser.invoke(
                null, socket, "GET", MonitorDeadline.after(Duration.ofSeconds(1)));
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
