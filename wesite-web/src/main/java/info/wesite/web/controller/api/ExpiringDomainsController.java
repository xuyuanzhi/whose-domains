package info.wesite.web.controller.api;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.service.DomainService;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Expiring Domains API
 * 展示即将过期的域名列表，结果缓存在 Redis，60分钟刷新一次，响应极速
 */
@Tag(name = "Expiring Domains API")
@RestController
@RequestMapping("/api")
public class ExpiringDomainsController {

    private static final Logger log = LoggerFactory.getLogger(ExpiringDomainsController.class);

    /** Redis key 前缀，格式: expiring:domains:{days}:{page} */
    private static final String CACHE_KEY_PREFIX = "expiring:domains:";
    /** 缓存过期时间：60 分钟 */
    private static final long CACHE_TTL_MINUTES = 60;

    private static final int PAGE_SIZE = 50;
    /** days 允许范围 */
    private static final int DAYS_MIN = 1;
    private static final int DAYS_MAX = 365;

    @Autowired
    private DomainService domainService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取即将过期的域名列表（有 Redis 缓存，不含 tld 过滤）
     *
     * @param days 未来几天内过期（默认30，最大365）
     * @param page 页码（默认1）
     */
    @Operation(summary = "List domains expiring within specified days (cached)")
    @GetMapping("/expiring-domains")
    @SuppressWarnings("unchecked")
    public ResponseJson<Map<String, Object>> listExpiringDomains(
            @RequestParam(value = "days", defaultValue = "30") int days,
            @RequestParam(value = "page", defaultValue = "1") int page,
            HttpServletRequest request) {

        // 基础限流，防止缓存穿透时的 DB 冲击
        String ip = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(ip, 30, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        // 参数范围限制
        if (days < DAYS_MIN) days = DAYS_MIN;
        if (days > DAYS_MAX) days = DAYS_MAX;
        if (page < 1) page = 1;

        String cacheKey = CACHE_KEY_PREFIX + days + ":" + page;

        try {
            // 1. 优先读取 Redis 缓存
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                Map<String, Object> data = JSON.parseObject(cached, Map.class);
                return ResponseJson.success(data);
            }

            // 2. 缓存未命中，查数据库
            Date now = new Date();
            Date futureDate = DateUtils.addDays(now, days);
            String nowStr = new java.text.SimpleDateFormat("yyyy-MM-dd").format(now);
            String futureStr = new java.text.SimpleDateFormat("yyyy-MM-dd").format(futureDate);

            long total = domainService.countExpiringDomains(nowStr, futureStr);
            int totalPages = (total == 0) ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);

            // page 上限校验（查完 total 才能判断）
            if (page > totalPages) page = totalPages;

            var records = domainService.listExpiringDomains(nowStr, futureStr, page, PAGE_SIZE);

            // 3. 组装响应
            java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
            if (records != null) {
                for (info.wesite.core.entity.Domain d : records) {
                    java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("name", d.getName());
                    item.put("expiryDate", d.getRegistExpiryDateText());
                    item.put("registrar", d.getRegistrar());
                    item.put("registrantOrg", d.getRegistrantOrg());
                    item.put("registrantCountry", d.getRegistrantCountry());
                    item.put("creationDate", d.getRegistCreateDateText());
                    item.put("tld", d.getTldName());
                    if (d.getExpiryDate() != null) {
                        long daysLeft = (d.getExpiryDate().getTime() - now.getTime()) / (24 * 60 * 60 * 1000);
                        item.put("daysLeft", daysLeft);
                    }
                    items.add(item);
                }
            }

            java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("domains", items);
            response.put("total", total);
            response.put("page", page);
            response.put("pageSize", PAGE_SIZE);
            response.put("totalPages", totalPages);
            response.put("days", days);

            // 4. 写入 Redis，TTL 60 分钟
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(response), CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception redisEx) {
                log.warn("Failed to write expiring domains cache to Redis, key={}", cacheKey, redisEx);
            }

            RateLimitUtils.incrementRequestCount(ip);
            return ResponseJson.success(response);

        } catch (Exception e) {
            log.error("Error listing expiring domains", e);
            return ResponseJson.failure("Failed to list expiring domains: " + e.getMessage());
        }
    }
}
