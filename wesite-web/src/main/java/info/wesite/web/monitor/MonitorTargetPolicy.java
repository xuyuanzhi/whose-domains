package info.wesite.web.monitor;

import java.io.IOException;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/** Shared outbound target policy for monitoring probes. */
final class MonitorTargetPolicy {

    /** IANA IPv4 special-purpose entries that are explicitly globally reachable. */
    private static final List<IpPrefix> GLOBALLY_REACHABLE_IPV4_EXCEPTIONS = List.of(
        ipv4Prefix("192.0.0.9", 32), ipv4Prefix("192.0.0.10", 32));

    /** IANA IPv4 ranges that are not globally reachable (deprecated entries fail closed). */
    private static final List<IpPrefix> NON_GLOBAL_IPV4 = List.of(
        ipv4Prefix("0.0.0.0", 8), ipv4Prefix("10.0.0.0", 8),
        ipv4Prefix("100.64.0.0", 10), ipv4Prefix("127.0.0.0", 8),
        ipv4Prefix("169.254.0.0", 16), ipv4Prefix("172.16.0.0", 12),
        ipv4Prefix("192.0.0.0", 24), ipv4Prefix("192.0.2.0", 24),
        ipv4Prefix("192.88.99.0", 24), ipv4Prefix("192.168.0.0", 16),
        ipv4Prefix("198.18.0.0", 15), ipv4Prefix("198.51.100.0", 24),
        ipv4Prefix("203.0.113.0", 24), ipv4Prefix("224.0.0.0", 4),
        ipv4Prefix("240.0.0.0", 4));

    /** Allocated entries from the IANA IPv6 Global Unicast registry (2025-10). */
    private static final List<IpPrefix> ALLOCATED_IPV6_GLOBAL_UNICAST = List.of(
        prefix("2001:200::", 23), prefix("2001:400::", 23), prefix("2001:600::", 23),
        prefix("2001:800::", 22), prefix("2001:c00::", 23), prefix("2001:e00::", 23),
        prefix("2001:1200::", 23), prefix("2001:1400::", 22), prefix("2001:1800::", 23),
        prefix("2001:1a00::", 23), prefix("2001:1c00::", 22), prefix("2001:2000::", 19),
        prefix("2001:4000::", 23), prefix("2001:4200::", 23), prefix("2001:4400::", 23),
        prefix("2001:4600::", 23), prefix("2001:4800::", 23), prefix("2001:4a00::", 23),
        prefix("2001:4c00::", 22), prefix("2001:5000::", 20), prefix("2001:8000::", 19),
        prefix("2001:a000::", 20), prefix("2001:b000::", 20), prefix("2003::", 18),
        prefix("2400::", 12), prefix("2410::", 12), prefix("2600::", 12),
        prefix("2610::", 23), prefix("2620::", 23), prefix("2630::", 12),
        prefix("2800::", 12), prefix("2a00::", 12), prefix("2a10::", 12),
        prefix("2c00::", 12));

    /** More-specific globally reachable exceptions inside IANA's default-deny 2001::/23. */
    private static final List<IpPrefix> GLOBALLY_REACHABLE_IETF_EXCEPTIONS = List.of(
        prefix("2001:1::1", 128), prefix("2001:1::2", 128), prefix("2001:1::3", 128),
        prefix("2001:3::", 32), prefix("2001:4:112::", 48),
        prefix("2001:20::", 28), prefix("2001:30::", 28));
    private static final IpPrefix IETF_PROTOCOL_ASSIGNMENTS = prefix("2001::", 23);
    private static final IpPrefix DOCUMENTATION_2001_DB8 = prefix("2001:db8::", 32);

    private MonitorTargetPolicy() {
    }

    static ResolvedTarget resolvePublic(
        String rawHost,
        MonitorDeadline deadline,
        HostResolver resolver) throws IOException {
        String host = normalizeHost(rawHost);
        InetAddress[] addresses = resolver.resolve(host, deadline);
        if (addresses == null || addresses.length == 0) {
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
            return matchesAny(bytes, GLOBALLY_REACHABLE_IPV4_EXCEPTIONS)
                || !matchesAny(bytes, NON_GLOBAL_IPV4);
        }

        if (isPrefix(bytes, new int[] {0x00, 0x64, 0xff, 0x9b}, 32)
            && allZero(bytes, 4, 12)) {
            return isPublicIpv4(bytes, 12);
        }
        if (isPrefix(bytes, new int[] {0x20, 0x02}, 16)) {
            return isPublicIpv4(bytes, 2);
        }
        if (matches(bytes, IETF_PROTOCOL_ASSIGNMENTS)) {
            return matchesAny(bytes, GLOBALLY_REACHABLE_IETF_EXCEPTIONS);
        }
        if (matches(bytes, DOCUMENTATION_2001_DB8)) {
            return false;
        }
        return matchesAny(bytes, ALLOCATED_IPV6_GLOBAL_UNICAST);
    }

    private static boolean isPublicIpv4(byte[] bytes, int offset) {
        try {
            return isPublic(InetAddress.getByAddress(java.util.Arrays.copyOfRange(bytes, offset, offset + 4)));
        } catch (UnknownHostException impossible) {
            return false;
        }
    }

    private static boolean allZero(byte[] bytes, int start, int end) {
        for (int index = start; index < end; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrefix(byte[] value, int[] prefix, int bits) {
        int wholeBytes = bits / 8;
        int remainingBits = bits % 8;
        for (int index = 0; index < wholeBytes; index++) {
            if ((value[index] & 0xff) != prefix[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return ((value[wholeBytes] & 0xff) & mask) == (prefix[wholeBytes] & mask);
    }

    private static boolean matchesAny(byte[] address, List<IpPrefix> prefixes) {
        return prefixes.stream().anyMatch(prefix -> matches(address, prefix));
    }

    private static boolean matches(byte[] address, IpPrefix prefix) {
        byte[] network = prefix.network;
        if (address.length != network.length) {
            return false;
        }
        int wholeBytes = prefix.bits() / 8;
        int remainingBits = prefix.bits() % 8;
        for (int index = 0; index < wholeBytes; index++) {
            if (address[index] != network[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return ((address[wholeBytes] & 0xff) & mask) == ((network[wholeBytes] & 0xff) & mask);
    }

    private static IpPrefix prefix(String literal, int bits) {
        try {
            byte[] address = InetAddress.getByName(literal).getAddress();
            if (address.length != 16 || bits < 0 || bits > 128) {
                throw new IllegalArgumentException("Invalid IPv6 prefix " + literal + "/" + bits);
            }
            return new IpPrefix(address, bits);
        } catch (UnknownHostException invalidLiteral) {
            throw new ExceptionInInitializerError(invalidLiteral);
        }
    }

    private static IpPrefix ipv4Prefix(String literal, int bits) {
        try {
            byte[] address = InetAddress.getByName(literal).getAddress();
            if (address.length != 4 || bits < 0 || bits > 32) {
                throw new IllegalArgumentException("Invalid IPv4 prefix " + literal + "/" + bits);
            }
            return new IpPrefix(address, bits);
        } catch (UnknownHostException invalidLiteral) {
            throw new ExceptionInInitializerError(invalidLiteral);
        }
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
        if (value.startsWith("[") || value.endsWith("]")) {
            if (!value.startsWith("[") || !value.endsWith("]") || value.length() < 3) {
                throw new UnknownHostException("invalid host");
            }
            value = value.substring(1, value.length() - 1);
        }
        if (value.indexOf(':') >= 0) {
            try {
                if (!(InetAddress.getByName(value) instanceof Inet6Address)) {
                    throw new UnknownHostException("invalid host");
                }
                return value.toLowerCase(java.util.Locale.ROOT);
            } catch (IllegalArgumentException invalid) {
                UnknownHostException failure = new UnknownHostException("invalid host");
                failure.initCause(invalid);
                throw failure;
            }
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

    private record IpPrefix(byte[] network, int bits) {
        private IpPrefix {
            network = Arrays.copyOf(network, network.length);
        }

        @Override
        public byte[] network() {
            return Arrays.copyOf(network, network.length);
        }
    }

    static final class BlockedTargetException extends IOException {
        BlockedTargetException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host, MonitorDeadline deadline) throws IOException;
    }
}
