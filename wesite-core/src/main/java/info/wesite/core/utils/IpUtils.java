package info.wesite.core.utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

public class IpUtils {

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
    
    public static String getRequestIp(HttpServletRequest request) {
    	String ip = request.getHeader("x-forwarded-for");
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("Proxy-Client-IP");
		}
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("WL-Proxy-Client-IP");
		}
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
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
