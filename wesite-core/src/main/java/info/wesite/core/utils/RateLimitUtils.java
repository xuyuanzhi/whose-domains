package info.wesite.core.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimitUtils {
    
    // 存储每个IP的请求计数
    private static final ConcurrentHashMap<String, IpRequestTracker> requestMap = new ConcurrentHashMap<>();
    
    // 默认时间窗口（毫秒）
    private static final long TIME_WINDOW_MS = 60000; // 1分钟
    
    // 默认最大请求数
    private static final int MAX_REQUESTS_PER_WINDOW = 10;
    
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
     * 检查指定IP是否超过请求限制
     * @param ip IP地址
     * @param maxRequests 时间窗口内的最大请求数
     * @param timeWindowMs 时间窗口大小（毫秒）
     * @return true 如果未超过限制，false 如果超过限制
     */
    public static boolean isAllowed(String ip, int maxRequests, long timeWindowMs) {
        cleanExpiredEntries();
        
        IpRequestTracker tracker = requestMap.get(ip);
        long currentTime = System.currentTimeMillis();
        
        if (tracker == null) {
            // 首次请求，创建新的跟踪器
            requestMap.put(ip, new IpRequestTracker());
            return true;
        }
        
        // 检查是否还在当前时间窗口内
        if (currentTime - tracker.getWindowStartTime() > timeWindowMs) {
            // 时间窗口已过期，重置计数器
            requestMap.put(ip, new IpRequestTracker());
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