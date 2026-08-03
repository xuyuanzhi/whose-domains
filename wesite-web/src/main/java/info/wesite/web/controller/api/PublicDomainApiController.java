package info.wesite.web.controller.api;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import info.wesite.core.entity.ApiKey;
import info.wesite.core.entity.Domain;
import info.wesite.core.service.ApiKeyService;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.ApiKeyUtils;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1")
public class PublicDomainApiController {
    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";
    private final ApiKeyService apiKeyService;
    private final DomainService domainService;
    private final StringRedisTemplate redis;
    public PublicDomainApiController(ApiKeyService apiKeyService, DomainService domainService, StringRedisTemplate redis) {
        this.apiKeyService = apiKeyService; this.domainService = domainService; this.redis = redis;
    }
    @GetMapping("/domains/{domainName}")
    public ResponseEntity<?> domain(@PathVariable String domainName, @RequestHeader(value="X-API-Key", required=false) String key, HttpServletRequest request) {
        if (!RateLimitUtils.isAllowed(IpUtils.getRequestIp(request), 120, 60_000)) return ResponseEntity.status(429).body(Map.of("error", "IP rate limit exceeded."));
        if (key == null || key.isBlank()) return ResponseEntity.status(401).body(Map.of("error", "Missing X-API-Key."));
        ApiKey apiKey = apiKeyService.getOne(new QueryWrapper<ApiKey>().eq("KEY_HASH", ApiKeyUtils.hash(key)).isNull("REVOKED_AT"));
        if (apiKey == null) return ResponseEntity.status(401).body(Map.of("error", "Invalid API key."));
        String normalized = domainName.trim().toLowerCase();
        if (!normalized.matches(DOMAIN_REGEX)) return ResponseEntity.badRequest().body(Map.of("error", "Invalid domain name."));
        String usageKey = "api:usage:" + apiKey.getUserId() + ":" + LocalDate.now();
        Long used;
        try {
            used = redis.opsForValue().increment(usageKey);
            redis.opsForSet().add("api:usage:active", apiKey.getUserId() + ":" + LocalDate.now());
            if (used != null && used == 1) redis.expire(usageKey, java.time.Duration.ofDays(2));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "Usage metering is temporarily unavailable."));
        }
        if (used == null) return ResponseEntity.status(503).body(Map.of("error", "Usage metering is temporarily unavailable."));
        if (used > 100) return ResponseEntity.status(429).body(Map.of("error", "Daily account quota exceeded."));
        apiKey.setLastUsedAt(new java.util.Date()); apiKeyService.updateById(apiKey);
        String main = DomainUtils.getMainDomain(normalized);
        Domain domain = domainService.getOne(new QueryWrapper<Domain>().eq("NAME", main).eq("STATUS", Domain.STATUS_ACTIVE));
        if (domain == null) return ResponseEntity.status(404).body(Map.of("error", "Domain not found. Search it on the website first."));
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("domain", domain.getName()); data.put("registrar", domain.getRegistrar());
        data.put("createdAt", domain.getRegistCreateDateText()); data.put("updatedAt", domain.getRegistUpdateDateText());
        data.put("expiresAt", domain.getRegistExpiryDateText()); data.put("status", domain.getDomainStatus());
        data.put("nameServers", domain.getNameServerList()); data.put("dnssec", domain.getDnssec());
        data.put("whoisServer", domain.getFinalWhoisServer()); data.put("rdapServer", domain.getRdapServer());
        return ResponseEntity.ok(Map.of("data", data, "quota", Map.of("dailyLimit",100,"used",used)));
    }
}
