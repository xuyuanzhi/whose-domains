package info.wesite.web.monitor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Small HTTP/1.1 client that connects to the exact address approved by the
 * target policy while retaining the original Host header, TLS SNI, and HTTPS
 * hostname verification.
 */
@Component
final class BoundHttpClient {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_LINE_BYTES = 65_536;
    private static final int MAX_HEADER_SECTION_BYTES = 65_536;
    private static final int MAX_HEADER_FIELDS = 100;
    private static final int MAX_CHUNK_LINE_BYTES = 1_024;
    private static final int STEP_TIMEOUT_MS = 5_000;

    private final MonitorTargetPolicy.HostResolver resolver;
    private final AddressTransport transport;

    @Autowired
    BoundHttpClient(MonitorTargetPolicy.HostResolver resolver) {
        this(resolver, BoundHttpClient::exchangeBound);
    }

    BoundHttpClient(
        MonitorTargetPolicy.HostResolver resolver,
        AddressTransport transport) {
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    Response execute(URI initialUri, String method, MonitorDeadline deadline) throws IOException {
        URI uri = initialUri;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            deadline.throwIfExpired();
            requireHttpUri(uri);
            MonitorTargetPolicy.ResolvedTarget target = MonitorTargetPolicy.resolvePublic(
                uri.getHost(), deadline, resolver);
            deadline.throwIfExpired();
            Response response = exchangeAddresses(uri, method, target, deadline);
            if (response.status() < 300 || response.status() >= 400 || redirects == MAX_REDIRECTS) {
                return response;
            }
            String location = response.headers().get("location");
            if (StringUtils.isBlank(location)) {
                return response;
            }
            uri = uri.resolve(location);
        }
        throw new IOException("HTTP redirect limit exceeded");
    }

    private Response exchangeAddresses(
        URI uri,
        String method,
        MonitorTargetPolicy.ResolvedTarget target,
        MonitorDeadline deadline) throws IOException {
        IOException lastFailure = null;
        for (java.net.InetAddress address : target.addresses()) {
            deadline.throwIfExpired();
            try {
                return transport.exchange(uri, method, address, deadline);
            } catch (IOException failure) {
                lastFailure = failure;
            }
        }
        deadline.throwIfExpired();
        throw lastFailure == null ? new IOException("No approved target address") : lastFailure;
    }

    private static Response exchangeBound(
        URI uri,
        String method,
        java.net.InetAddress address,
        MonitorDeadline deadline) throws IOException {
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() >= 0 ? uri.getPort() : tls ? 443 : 80;
        Socket plain = new Socket();
        Socket active = plain;
        try {
            plain.connect(new InetSocketAddress(address, port), deadline.timeoutMillis(STEP_TIMEOUT_MS));
            deadline.throwIfExpired();
            if (tls) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(plain, tlsPeerHost(uri), port, true);
                active = ssl;
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                String sniHost = sniHost(uri);
                if (sniHost != null) {
                    parameters.setServerNames(List.of(new SNIHostName(sniHost)));
                }
                ssl.setSSLParameters(parameters);
                ssl.setSoTimeout(deadline.timeoutMillis(STEP_TIMEOUT_MS));
                ssl.startHandshake();
                deadline.throwIfExpired();
            }

            active.setSoTimeout(deadline.timeoutMillis(STEP_TIMEOUT_MS));
            writeRequest(active.getOutputStream(), uri, method);
            return readResponse(active, method, deadline);
        } finally {
            try {
                active.close();
            } catch (IOException ignored) {
            }
            if (active != plain) {
                try {
                    plain.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void writeRequest(OutputStream output, URI uri, String method) throws IOException {
        String path = StringUtils.defaultIfBlank(uri.getRawPath(), "/");
        if (StringUtils.isNotBlank(uri.getRawQuery())) {
            path += "?" + uri.getRawQuery();
        }
        String request = method + " " + path + " HTTP/1.1\r\n"
            + "Host: " + httpHostAuthority(uri) + "\r\n"
            + "User-Agent: WhoseDomains-Monitor/1.0\r\n"
            + "Accept: application/rdap+json, application/json, */*\r\n"
            + "Accept-Encoding: identity\r\n"
            + "Connection: close\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static Response readResponse(
        Socket socket,
        String method,
        MonitorDeadline deadline) throws IOException {
        DeadlineInput input = new DeadlineInput(
            new BufferedInputStream(socket.getInputStream()), socket, deadline);
        HeaderBudget headerBudget = new HeaderBudget();
        String statusLine = readHeaderLine(input, deadline, headerBudget);
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2) {
            throw new IOException("Malformed HTTP status line");
        }
        int status;
        try {
            status = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException malformed) {
            throw new IOException("Malformed HTTP status", malformed);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readHeaderLine(input, deadline, headerBudget)).isEmpty()) {
            headerBudget.recordField();
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Malformed HTTP header");
            }
            headers.putIfAbsent(
                line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                line.substring(separator + 1).trim());
        }

        byte[] body = "HEAD".equalsIgnoreCase(method)
            ? new byte[0]
            : readBody(input, headers, deadline);
        return new Response(status, headers, body);
    }

    private static byte[] readBody(
        DeadlineInput input,
        Map<String, String> headers,
        MonitorDeadline deadline) throws IOException {
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null
            && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(input, deadline);
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            int length;
            try {
                length = Integer.parseInt(contentLength);
            } catch (NumberFormatException malformed) {
                throw new IOException("Invalid Content-Length", malformed);
            }
            if (length < 0 || length > MAX_BODY_BYTES) {
                throw new IOException("HTTP response exceeded size limit");
            }
            return readExactly(input, length, deadline);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            deadline.throwIfExpired();
            appendBounded(body, buffer, count);
        }
        return body.toByteArray();
    }

    private static byte[] readChunked(DeadlineInput input, MonitorDeadline deadline) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input, deadline);
            if (sizeLine.length() > MAX_CHUNK_LINE_BYTES) {
                throw new IOException("HTTP chunk extension exceeded size limit");
            }
            String hex = sizeLine.split(";", 2)[0].trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException malformed) {
                throw new IOException("Invalid HTTP chunk size", malformed);
            }
            if (size < 0 || size > MAX_BODY_BYTES - body.size()) {
                throw new IOException("HTTP response exceeded size limit");
            }
            if (size == 0) {
                HeaderBudget trailerBudget = new HeaderBudget();
                String trailer;
                while (!(trailer = readHeaderLine(input, deadline, trailerBudget)).isEmpty()) {
                    trailerBudget.recordField();
                    if (trailer.indexOf(':') <= 0) {
                        throw new IOException("Malformed HTTP trailer");
                    }
                }
                return body.toByteArray();
            }
            byte[] chunk = readExactly(input, (int) size, deadline);
            appendBounded(body, chunk, chunk.length);
            if (!readLine(input, deadline).isEmpty()) {
                throw new IOException("Malformed HTTP chunk terminator");
            }
        }
    }

    private static byte[] readExactly(
        DeadlineInput input,
        int length,
        MonitorDeadline deadline) throws IOException {
        byte[] value = new byte[length];
        int offset = 0;
        while (offset < length) {
            deadline.throwIfExpired();
            int count = input.read(value, offset, length - offset);
            if (count < 0) {
                throw new IOException("Unexpected end of HTTP response");
            }
            offset += count;
        }
        return value;
    }

    private static String readLine(DeadlineInput input, MonitorDeadline deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean carriageReturn = false;
        while (true) {
            deadline.throwIfExpired();
            int value = input.read();
            if (value < 0) {
                throw new IOException("Unexpected end of HTTP headers");
            }
            if (carriageReturn && value == '\n') {
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            if (carriageReturn) {
                line.write('\r');
            }
            carriageReturn = value == '\r';
            if (!carriageReturn) {
                line.write(value);
            }
            if (line.size() > MAX_LINE_BYTES) {
                throw new IOException("HTTP header line exceeded size limit");
            }
        }
    }

    private static String readHeaderLine(
        DeadlineInput input,
        MonitorDeadline deadline,
        HeaderBudget budget) throws IOException {
        String line = readLine(input, deadline);
        budget.recordLine(line);
        return line;
    }

    private static void appendBounded(ByteArrayOutputStream target, byte[] value, int length)
        throws IOException {
        if (length < 0 || length > MAX_BODY_BYTES - target.size()) {
            throw new IOException("HTTP response exceeded size limit");
        }
        target.write(value, 0, length);
    }

    private static void requireHttpUri(URI uri) throws MonitorTargetPolicy.BlockedTargetException {
        String scheme = uri == null ? null : uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
            || uri.getHost() == null) {
            throw new MonitorTargetPolicy.BlockedTargetException("Target must be HTTP(S)");
        }
    }

    static String tlsPeerHost(URI uri) {
        String host = uri.getHost();
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    static String sniHost(URI uri) {
        String uriHost = uri.getHost();
        String peerHost = tlsPeerHost(uri);
        return uriHost != null && !uriHost.startsWith("[") && containsLetter(peerHost)
            ? peerHost
            : null;
    }

    static String httpHostAuthority(URI uri) {
        int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return uri.getPort() < 0 || uri.getPort() == defaultPort
            ? uri.getHost()
            : uri.getHost() + ":" + uri.getPort();
    }

    private static boolean containsLetter(String value) {
        return value != null && value.chars().anyMatch(Character::isLetter);
    }

    record Response(int status, Map<String, String> headers, byte[] body) {
        Response {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body.clone();
        }
    }

    @FunctionalInterface
    interface AddressTransport {
        Response exchange(
            URI uri,
            String method,
            java.net.InetAddress address,
            MonitorDeadline deadline) throws IOException;
    }

    private static final class HeaderBudget {
        private int bytes;
        private int fields;

        private void recordLine(String line) throws IOException {
            int lineBytes = line.length() + 2;
            if (lineBytes > MAX_HEADER_SECTION_BYTES - bytes) {
                throw new IOException("HTTP header section exceeded size limit");
            }
            bytes += lineBytes;
        }

        private void recordField() throws IOException {
            fields++;
            if (fields > MAX_HEADER_FIELDS) {
                throw new IOException("HTTP header field count exceeded limit");
            }
        }
    }

    private static final class DeadlineInput extends InputStream {
        private final InputStream delegate;
        private final Socket socket;
        private final MonitorDeadline deadline;

        private DeadlineInput(InputStream delegate, Socket socket, MonitorDeadline deadline) {
            this.delegate = delegate;
            this.socket = socket;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            socket.setSoTimeout(deadline.timeoutMillis(STEP_TIMEOUT_MS));
            int result = delegate.read();
            deadline.throwIfExpired();
            return result;
        }

        @Override
        public int read(byte[] value, int offset, int length) throws IOException {
            socket.setSoTimeout(deadline.timeoutMillis(STEP_TIMEOUT_MS));
            int result = delegate.read(value, offset, length);
            deadline.throwIfExpired();
            return result;
        }
    }

}
