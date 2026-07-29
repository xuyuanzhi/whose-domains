package info.wesite.core.utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

public class IpUtils {

    /** 严格 IPv4：每段 0-255 */
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    /** 单个 IPv6 分组：1-4 位十六进制 */
    private static final Pattern IPV6_GROUP = Pattern.compile("^[0-9a-fA-F]{1,4}$");

    public static List<String> getIps(String domainName) throws UnknownHostException {
        InetAddress[] ipList = Inet4Address.getAllByName(domainName);
        if (ipList == null || ipList.length == 0) {
            return null;
        }

        if (ipList.length == 1) {
            return Arrays.asList(new String[] { ipList[0].getHostAddress() });
        }

        List<String> list = Arrays.asList(ipList).stream().map(item -> {
            return item.getHostAddress();
        }).collect(Collectors.toList());

        return list;
    }

    /**
     * 获取请求的真实客户端 IP。
     *
     * 关键：只有当直接对端（remoteAddr）是可信反向代理（本机 / 内网地址）时，才采信
     * CF-Connecting-IP / X-Forwarded-For 转发头；否则说明客户端是直连源站的，一律使用对端地址。
     * 这样在源站被直连时无法通过伪造转发头来伪造身份，从而绕过限流或伪造 API token 的绑定。
     *
     * XFF 从右到左扫描、取第一个「合法且非内网」的地址：反向代理 proxy_add_x_forwarded_for
     * 会把真实对端追加在链条末位，跳过内网/代理跳数即得最接近的真实公网客户端，同时避开
     * 客户端在链首自行伪造的公网地址。
     */
    public static String getRequestIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        if (!isTrustedProxyPeer(remoteAddr)) {
            return normalize(remoteAddr);
        }

        String cf = request.getHeader("CF-Connecting-IP");
        if (isValidIp(cf)) {
            return normalize(cf.trim());
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (isValidIp(candidate) && !isTrustedProxyPeer(candidate)) {
                    return normalize(candidate);
                }
            }
        }

        return normalize(remoteAddr);
    }

    /**
     * 网段标识：IPv4 取 /24，IPv6 取 /48。用于网段级限流聚合与 API token 的网段绑定。
     * 基于 InetAddress 解析出的字节计算，天然规范化压缩形式与 IPv4 映射地址，
     * 避免字符串切分导致同一网段生成不同 key。
     */
    public static String subnetId(String ip) {
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length == 4) {
                return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".0/24";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                sb.append(Integer.toHexString(b[i] & 0xff));
            }
            return sb.append("::/48").toString();
        } catch (Exception e) {
            return ip;
        }
    }

    /**
     * 直接对端是否为可信反向代理：本机回环、内网（RFC1918 / 链路本地）、IPv6 唯一本地地址（fc00::/7）。
     */
    private static boolean isTrustedProxyPeer(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress()
                    || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
                return true;
            }
            byte[] b = a.getAddress();
            return b.length == 16 && (b[0] & 0xfe) == 0xfc; // fc00::/7
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验字符串是否为合法 IPv4 / IPv6 字面量（拒绝 "ip:port"、超范围段值等伪造值，
     * 避免它们成为限流 key 或 token 绑定值）。不触发 DNS 解析。
     */
    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            return false;
        }
        ip = ip.trim();
        if (IPV4.matcher(ip).matches()) {
            return true;
        }
        return ip.indexOf(':') >= 0 && isValidIpv6(ip);
    }

    /**
     * 结构化校验 IPv6 字面量（含 IPv4 映射后缀），不做 DNS 查询。
     * 关键点：拒绝 "203.0.113.7:47215" 这类 IPv4:port —— 其首组含 '.' 且非十六进制，会被判非法。
     */
    private static boolean isValidIpv6(String ip) {
        String[] groups = ip.split(":", -1);
        if (groups.length < 3 || groups.length > 8) {
            return false;
        }
        for (int i = 0; i < groups.length; i++) {
            String g = groups[i];
            if (g.isEmpty()) {
                continue; // "::" 造成的空组
            }
            if (i == groups.length - 1 && g.indexOf('.') >= 0) {
                if (!IPV4.matcher(g).matches()) {
                    return false; // 末组允许 IPv4 映射，但必须是严格 IPv4
                }
            } else if (!IPV6_GROUP.matcher(g).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String ip) {
        if (ip == null) {
            return "0.0.0.0";
        }
        return "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) ? "127.0.0.1" : ip;
    }

    public static void main(String[] args) {
        try {
            List<String> ips = getIps("a.com");
            System.out.println(ips);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }
}
