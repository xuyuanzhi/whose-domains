package info.wesite.web.task;

import java.time.LocalDate;
import java.util.Date;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import info.wesite.core.entity.ApiUsageDaily;
import info.wesite.core.service.ApiUsageDailyService;
import info.wesite.core.utils.RandomUtils;

@Component
public class ApiUsageFlushTask {
    private static final Logger log = LoggerFactory.getLogger(ApiUsageFlushTask.class);
    private static final String ACTIVE_KEY = "api:usage:active";
    private final StringRedisTemplate redis;
    private final ApiUsageDailyService usage;

    public ApiUsageFlushTask(StringRedisTemplate redis, ApiUsageDailyService usage) { this.redis = redis; this.usage = usage; }

    @Scheduled(cron = "0 */10 * * * *")
    public void flush() {
        try {
            Set<String> items = redis.opsForSet().members(ACTIVE_KEY);
            if (items == null || items.isEmpty()) return;
            for (String item : items) flushOne(item);
        } catch (Exception e) { log.error("Failed to flush API usage", e); }
    }

    private void flushOne(String item) {
        try {
            String[] parts = item.split(":", 2);
            if (parts.length != 2 || LocalDate.parse(parts[1]).isBefore(LocalDate.now().minusDays(1))) { redis.opsForSet().remove(ACTIVE_KEY, item); return; }
            String value = redis.opsForValue().get("api:usage:" + item);
            if (value == null) { redis.opsForSet().remove(ACTIVE_KEY, item); return; }
            int count = Integer.parseInt(value);
            ApiUsageDaily row = usage.getOne(new QueryWrapper<ApiUsageDaily>().eq("USER_ID", parts[0]).eq("USAGE_DATE", parts[1]));
            if (row == null) { row = new ApiUsageDaily(); row.setId(RandomUtils.generateId()); row.setUserId(parts[0]); row.setUsageDate(parts[1]); row.setCreateBy(parts[0]); row.setCreateTime(new Date()); usage.save(row); }
            row.setRequestCount(count); row.setUpdateTime(new Date()); usage.updateById(row);
        } catch (Exception e) { log.warn("Failed to flush API usage entry {}", item, e); }
    }
}
