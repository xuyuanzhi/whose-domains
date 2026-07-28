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

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

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
     * 取值优先级：
     * 1. CF-Connecting-IP —— 经 Cloudflare 回源时由 CF 覆写；源站只接受 CF 流量的前提下不可伪造
     * 2. X-Forwarded-For 的最后一个值 —— 客户端可以自带伪造的 XFF，自建反代
     *    （proxy_add_x_forwarded_for）会把真实对端地址追加在链条末位，
     *    因此取最后一个值而不是第一个，防止伪造 IP 绕过限流
     * 3. remoteAddr 兜底
     */
    public static String getRequestIp(HttpServletRequest request) {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (isValidIp(cfIp)) {
            return normalize(cfIp.trim());
        }

        String xff = request.getHeader("x-forwarded-for");
        if (xff != null && !xff.isEmpty() && !"unknown".equalsIgnoreCase(xff)) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].trim();
            if (isValidIp(last)) {
                return normalize(last);
            }
        }

        return normalize(request.getRemoteAddr());
    }

    /**
     * 校验字符串是否为合法的 IPv4/IPv6 形态（防止把伪造的垃圾头当作限流 key）
     */
    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            return false;
        }
        ip = ip.trim();
        if (IPV4.matcher(ip).matches()) {
            return true;
        }
        return ip.indexOf(':') >= 0 && IPV6_CHARS.matcher(ip).matches();
    }

    private static String normalize(String ip) {
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
