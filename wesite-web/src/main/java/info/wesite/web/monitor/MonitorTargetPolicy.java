package info.wesite.web.monitor;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Shared outbound target policy for monitoring probes. */
final class MonitorTargetPolicy {

    private MonitorTargetPolicy() {
    }

    static ResolvedTarget resolvePublic(String rawHost) throws IOException {
        return resolvePublic(rawHost, InetAddress::getAllByName);
    }

    static ResolvedTarget resolvePublic(String rawHost, HostResolver resolver) throws IOException {
        String host = normalizeHost(rawHost);
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new BlockedTargetException("Private or special-purpose target is not allowed");
            }
        }
        return new ResolvedTarget(host, List.of(addresses));
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return false;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            if (first == 0 || first == 127 || first >= 224) {
                return false;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return false;
            }
            if (first == 192 && second == 0 && (third == 0 || third == 2)) {
                return false;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false;
            }
            if (first == 198 && second == 51 && third == 100) {
                return false;
            }
            return !(first == 203 && second == 0 && third == 113);
        }

        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if ((first & 0xfe) == 0xfc) {
            return false;
        }
        return !(first == 0x20 && second == 0x01
            && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8);
    }

    private static String normalizeHost(String rawHost) throws UnknownHostException {
        if (rawHost == null) {
            throw new UnknownHostException("missing host");
        }
        String value = rawHost.trim();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || value.indexOf('/') >= 0 || value.indexOf('@') >= 0) {
            throw new UnknownHostException("invalid host");
        }
        try {
            return IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            UnknownHostException failure = new UnknownHostException("invalid host");
            failure.initCause(invalid);
            throw failure;
        }
    }

    record ResolvedTarget(String host, List<InetAddress> addresses) {
    }

    static final class BlockedTargetException extends IOException {
        BlockedTargetException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws IOException;
    }
}
