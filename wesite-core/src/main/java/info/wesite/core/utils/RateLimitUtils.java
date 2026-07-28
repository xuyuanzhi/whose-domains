package info.wesite.core.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimitUtils {

    // 存储每个IP（及网段聚合key）的请求计数
    private static final ConcurrentHashMap<String, IpRequestTracker> requestMap = new ConcurrentHashMap<>();

    // 默认时间窗口（毫秒）
    private static final long TIME_WINDOW_MS = 60000; // 1分钟

    // 默认最大请求数
    private static final int MAX_REQUESTS_PER_WINDOW = 10;

    // 网段聚合限流倍数：单 IP 限 N 次时，同一 /24 网段（IPv6 取 /48）合计限 N * 倍数。
    // 用于对付轮换代理池——它们单 IP 请求数很低，但同网段合计非常高
    private static final int SUBNET_MULTIPLIER = 5;

    public static class IpRequestTracker {
        private final AtomicInteger requestCount;
        private final long windowStartTime;

        public IpRequestTracker() {
            this.requestCount = new AtomicInteger(1);
            this.windowStartTime = System.currentTimeMillis();
        }

        public int increment() {
            return requestCount.incrementAndGet();
        }

        public int getCount() {
            return requestCount.get();
        }

        public long getWindowStartTime() {
            return windowStartTime;
        }
    }

    /**
     * 检查指定IP是否超过请求限制
     * @param ip IP地址
     * @return true 如果未超过限制，false 如果超过限制
     */
    public static boolean isAllowed(String ip) {
        return isAllowed(ip, MAX_REQUESTS_PER_WINDOW, TIME_WINDOW_MS);
    }

    /**
     * 检查指定IP是否超过请求限制。
     * 同时按 /24 网段（IPv6 /48）做聚合限流：网段合计超过 maxRequests * SUBNET_MULTIPLIER 时，
     * 该网段内所有 IP 一并拒绝。
     * @param ip IP地址
     * @param maxRequests 时间窗口内单 IP 的最大请求数
     * @param timeWindowMs 时间窗口大小（毫秒）
     * @return true 如果未超过限制，false 如果超过限制
     */
    public static boolean isAllowed(String ip, int maxRequests, long timeWindowMs) {
        cleanExpiredEntries();

        // 先查网段聚合，网段被封时不再给单 IP 计数
        String subnetKey = subnetKey(ip);
        if (subnetKey != null && !check(subnetKey, maxRequests * SUBNET_MULTIPLIER, timeWindowMs)) {
            return false;
        }

        return check(ip, maxRequests, timeWindowMs);
    }

    private static boolean check(String key, int maxRequests, long timeWindowMs) {
        IpRequestTracker tracker = requestMap.get(key);
        long currentTime = System.currentTimeMillis();

        if (tracker == null) {
            // 首次请求，创建新的跟踪器
            requestMap.put(key, new IpRequestTracker());
            return true;
        }

        // 检查是否还在当前时间窗口内
        if (currentTime - tracker.getWindowStartTime() > timeWindowMs) {
            // 时间窗口已过期，重置计数器
            requestMap.put(key, new IpRequestTracker());
            return true;
        }

        // 检查请求数是否超过限制
        int currentCount = tracker.getCount();
        if (currentCount >= maxRequests) {
            return false; // 超过限制
        }

        // 增加请求计数
        tracker.increment();
        return true;
    }

    /**
     * 生成网段聚合 key：IPv4 取 /24，IPv6 取前 3 组（约 /48）。无法识别时返回 null（不做网段限流）
     */
    private static String subnetKey(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        int lastDot = ip.lastIndexOf('.');
        if (ip.indexOf(':') < 0 && lastDot > 0) {
            return "subnet:" + ip.substring(0, lastDot);
        }
        if (ip.indexOf(':') >= 0) {
            String[] groups = ip.split(":");
            if (groups.length >= 3) {
                return "subnet:" + groups[0] + ":" + groups[1] + ":" + groups[2];
            }
        }
        return null;
    }

    /**
     * 手动增加请求计数
     * @param ip IP地址
     */
    public static void incrementRequestCount(String ip) {
        IpRequestTracker tracker = requestMap.get(ip);
        if (tracker != null) {
            tracker.increment();
        }
    }

    /**
     * 清理过期的条目
     */
    private static void cleanExpiredEntries() {
        long currentTime = System.currentTimeMillis();
        requestMap.entrySet().removeIf(entry -> {
            IpRequestTracker tracker = entry.getValue();
            return currentTime - tracker.getWindowStartTime() > TIME_WINDOW_MS;
        });
    }

    /**
     * 获取指定IP的当前请求数
     * @param ip IP地址
     * @return 当前请求数，如果不存在则返回0
     */
    public static int getRequestCount(String ip) {
        IpRequestTracker tracker = requestMap.get(ip);
        if (tracker == null) {
            return 0;
        }

        // 检查时间窗口是否已过期
        if (System.currentTimeMillis() - tracker.getWindowStartTime() > TIME_WINDOW_MS) {
            requestMap.remove(ip); // 移除过期条目
            return 0;
        }

        return tracker.getCount();
    }
}
