package info.wesite.core.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimitUtils {

    // 每个 key（单 IP / 网段）的请求计数
    private static final ConcurrentHashMap<String, Window> requestMap = new ConcurrentHashMap<>();

    // 默认时间窗口（毫秒）
    private static final long TIME_WINDOW_MS = 60000; // 1分钟

    // 默认最大请求数
    private static final int MAX_REQUESTS_PER_WINDOW = 10;

    // 网段（IPv4 /24、IPv6 /48）级别的固定全局配额：与各端点自身 limit 解耦，
    // 只做「一个网段整体每分钟最多这么多请求」的粗粒度兜底，用于拦截轮换代理池，
    // 不因某个高配额端点的正常流量把低配额端点的网段额度顶掉。
    private static final int SUBNET_MAX_PER_WINDOW = 300;
    private static final long SUBNET_WINDOW_MS = 60000;

    // 过期清理的最小间隔：避免每次请求都全量扫描整张表
    private static final long CLEANUP_INTERVAL_MS = 30000;
    private static final AtomicLong lastCleanup = new AtomicLong(0);

    /** 单个窗口计数；windowStart / windowMs 创建后不可变，count 仅在 compute 的分段锁内修改 */
    private static final class Window {
        final long windowStart;
        final long windowMs;
        int count;

        Window(long windowStart, long windowMs) {
            this.windowStart = windowStart;
            this.windowMs = windowMs;
            this.count = 1;
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
     * 检查指定IP是否超过请求限制。先判单 IP 限额；通过后再判所在网段的固定全局配额。
     * 被单 IP 限额拒绝的请求不会计入网段配额，因此单个 IP 无法把整个 /24 顶满连累邻居。
     * @param ip IP地址
     * @param maxRequests 时间窗口内单 IP 的最大请求数
     * @param timeWindowMs 时间窗口大小（毫秒）
     * @return true 如果未超过限制，false 如果超过限制
     */
    public static boolean isAllowed(String ip, int maxRequests, long timeWindowMs) {
        maybeCleanup();

        if (!tryAcquire(ip, maxRequests, timeWindowMs)) {
            return false;
        }

        String subnet = IpUtils.subnetId(ip);
        if (subnet != null && !subnet.equals(ip)
                && !tryAcquire("subnet:" + subnet, SUBNET_MAX_PER_WINDOW, SUBNET_WINDOW_MS)) {
            return false;
        }

        return true;
    }

    /**
     * 原子地在窗口内自增并判断是否仍在限额内。用 ConcurrentHashMap.compute 的分段锁保证
     * check-and-increment 不被并发突发击穿。
     */
    private static boolean tryAcquire(String key, int maxRequests, long timeWindowMs) {
        final long now = System.currentTimeMillis();
        final int[] countHolder = new int[1];
        requestMap.compute(key, (k, cur) -> {
            if (cur == null || now - cur.windowStart > cur.windowMs) {
                Window fresh = new Window(now, timeWindowMs);
                countHolder[0] = fresh.count;
                return fresh;
            }
            cur.count++;
            countHolder[0] = cur.count;
            return cur;
        });
        return countHolder[0] <= maxRequests;
    }

    /**
     * 成功处理业务后，为该请求追加一次计数（单 IP 与其网段一并计入，保持两侧口径一致）。
     * @param ip IP地址
     */
    public static void incrementRequestCount(String ip) {
        bump(ip);
        String subnet = IpUtils.subnetId(ip);
        if (subnet != null && !subnet.equals(ip)) {
            bump("subnet:" + subnet);
        }
    }

    private static void bump(String key) {
        requestMap.computeIfPresent(key, (k, cur) -> {
            cur.count++;
            return cur;
        });
    }

    /**
     * 过期清理：最多每 CLEANUP_INTERVAL_MS 全量扫描一次，且按每个 key 自己的窗口长度判定过期
     * （不再用固定的 TIME_WINDOW_MS 误删更长窗口的存活记录）。
     */
    private static void maybeCleanup() {
        long now = System.currentTimeMillis();
        long last = lastCleanup.get();
        if (now - last > CLEANUP_INTERVAL_MS && lastCleanup.compareAndSet(last, now)) {
            requestMap.entrySet().removeIf(entry -> {
                Window w = entry.getValue();
                return now - w.windowStart > w.windowMs;
            });
        }
    }

    /**
     * 获取指定IP的当前请求数
     * @param ip IP地址
     * @return 当前请求数，如果不存在或已过期则返回0
     */
    public static int getRequestCount(String ip) {
        Window w = requestMap.get(ip);
        if (w == null) {
            return 0;
        }
        if (System.currentTimeMillis() - w.windowStart > w.windowMs) {
            requestMap.remove(ip);
            return 0;
        }
        return w.count;
    }
}
