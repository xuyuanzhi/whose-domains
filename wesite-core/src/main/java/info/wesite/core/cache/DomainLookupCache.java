package info.wesite.core.cache;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;

import info.wesite.core.entity.Domain;

@Component
public class DomainLookupCache {

    private static final Logger log = LoggerFactory.getLogger(DomainLookupCache.class);
    private static final String KEY_PREFIX = "domain:lookup:v1:";
    private static final String HIT_KEY = "domain:lookup:metrics:hits";
    private static final String MISS_KEY = "domain:lookup:metrics:misses";
    private static final long TTL_HOURS = 24;

    private final StringRedisTemplate redis;

    public DomainLookupCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Domain get(String domainName) {
        try {
            String value = redis.opsForValue().get(key(domainName));
            if (value == null) {
                redis.opsForValue().increment(MISS_KEY);
                return null;
            }
            redis.opsForValue().increment(HIT_KEY);
            return JSON.parseObject(value, Domain.class, JSONReader.Feature.FieldBased);
        } catch (Exception e) {
            log.warn("Domain lookup cache unavailable; bypassing cache", e);
            return null;
        }
    }

    public void put(Domain domain) {
        if (domain == null || domain.getName() == null) return;
        try {
            redis.opsForValue().set(key(domain.getName()), JSON.toJSONString(domain, JSONWriter.Feature.FieldBased), TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Could not cache domain lookup for {}", domain.getName(), e);
        }
    }

    private String key(String domainName) {
        return KEY_PREFIX + domainName.toLowerCase(Locale.ROOT);
    }
}
