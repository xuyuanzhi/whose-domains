package info.wesite.web.task;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.entity.Domain;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.DomainUtils;

/**
 * 定时刷新"即将过期域名"缓存
 * 每小时主动预热 days=30/60/90 的前 N 页，确保用户命中缓存
 */
@Profile({ "prod", "mac" })
@Component
@EnableScheduling
public class ExpiringDomainsCacheTask {

    private static final Logger logger = LoggerFactory.getLogger(ExpiringDomainsCacheTask.class);

    /** Redis key 前缀，与 ExpiringDomainsController 保持一致 */
    private static final String CACHE_KEY_PREFIX = "expiring:domains:";
    private static final long CACHE_TTL_MINUTES = 60;
    private static final int PAGE_SIZE = 50;

    /** 预热的 days 列表：近30天、60天、90天 */
    private static final int[] WARM_UP_DAYS = { 30, 60, 90 };
    /** 每个 days 预热前多少页 */
    private static final int WARM_UP_PAGES = 5;

    @Autowired
    private DomainService domainService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 每小时整点执行，刷新常用分页缓存
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void refreshExpiringDomainsCache() {
        logger.info("开始刷新即将过期域名缓存...");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        Date now = new Date();

        for (int days : WARM_UP_DAYS) {
            String nowStr = sdf.format(now);
            String futureStr = sdf.format(DateUtils.addDays(now, days));

            try {
                long total = domainService.countExpiringDomains(nowStr, futureStr);
                int totalPages = (total == 0) ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);
                int pagesToWarm = Math.min(WARM_UP_PAGES, totalPages);

                for (int page = 1; page <= pagesToWarm; page++) {
                    try {
                        var records = domainService.listExpiringDomains(nowStr, futureStr, page, PAGE_SIZE);

                        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
                        if (records != null) {
                            for (Domain d : records) {
                                // 如果域名的过期时间在一个月内，续费几率大，主动更新下信息
                                if (d.getExpiryDate() != null) {
                                    long daysLeft = (d.getExpiryDate().getTime() - now.getTime()) / (24 * 60 * 60 * 1000);
                                    if (daysLeft <= 30) {
                                        try {
                                            if (DomainUtils.fillMainDomainInfo(d)) {
                                                d.setUpdateTime(new Date());
                                                d.setUpdateBy("CACHE_TASK");
                                                domainService.updateById(d);
                                            }
                                        } catch (Exception e) {
                                            logger.error("Failed to update expiring domain info: {}", d.getName(), e);
                                        }
                                    }
                                }

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

                        java.util.Map<String, Object> pageData = new java.util.LinkedHashMap<>();
                        pageData.put("domains", items);
                        pageData.put("total", total);
                        pageData.put("page", page);
                        pageData.put("pageSize", PAGE_SIZE);
                        pageData.put("totalPages", totalPages);
                        pageData.put("days", days);

                        String cacheKey = CACHE_KEY_PREFIX + days + ":" + page;
                        stringRedisTemplate.opsForValue().set(
                                cacheKey, JSON.toJSONString(pageData), CACHE_TTL_MINUTES, TimeUnit.MINUTES);

                        logger.debug("缓存刷新完成: key={}", cacheKey);
                    } catch (Exception pageEx) {
                        logger.error("刷新缓存失败: days={}, page={}", days, page, pageEx);
                    }
                }

                logger.info("days={} 缓存刷新完成，共预热 {} 页，total={}", days, pagesToWarm, total);
            } catch (Exception e) {
                logger.error("刷新 days={} 缓存时发生异常", days, e);
            }
        }

        logger.info("即将过期域名缓存刷新完毕");
    }
}
