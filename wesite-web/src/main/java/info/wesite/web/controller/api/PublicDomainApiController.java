package info.wesite.web.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import info.wesite.core.diagnostics.DiagnosticRecorder;
import info.wesite.core.entity.ApiKey;
import info.wesite.core.entity.ApiPlan;
import info.wesite.core.entity.Domain;
import info.wesite.core.model.QuotaDecision;
import info.wesite.core.service.ApiKeyService;
import info.wesite.core.service.ApiPlanService;
import info.wesite.core.service.ApiQuotaService;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.ApiKeyUtils;
import info.wesite.core.utils.DomainNameValidator;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1")
public class PublicDomainApiController {
    private final ApiKeyService apiKeyService;
    private final DomainService domainService;
    private final ApiPlanService apiPlanService;
    private final ApiQuotaService apiQuotaService;

    public PublicDomainApiController(ApiKeyService apiKeyService, DomainService domainService,
            ApiPlanService apiPlanService, ApiQuotaService apiQuotaService) {
        this.apiKeyService = apiKeyService;
        this.domainService = domainService;
        this.apiPlanService = apiPlanService;
        this.apiQuotaService = apiQuotaService;
    }

    @GetMapping("/domains/{domainName}")
    public ResponseEntity<?> domain(@PathVariable String domainName,
            @RequestHeader(value = "X-API-Key", required = false) String key, HttpServletRequest request) {
        if (!RateLimitUtils.isAllowed(IpUtils.getRequestIp(request), 120, 60_000)) {
            return ResponseEntity.status(429).body(Map.of("error", "IP rate limit exceeded."));
        }
        if (key == null || key.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing X-API-Key."));
        }
        ApiKey apiKey = apiKeyService.getOne(new QueryWrapper<ApiKey>().eq("KEY_HASH", ApiKeyUtils.hash(key)).isNull("REVOKED_AT"));
        if (apiKey == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid API key."));
        }
        String normalized = DomainNameValidator.normalize(domainName);
        if (normalized == null || !DomainNameValidator.isValid(normalized)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid domain name."));
        }
        if (apiPlanService == null || apiQuotaService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Usage metering is temporarily unavailable."));
        }
        ApiPlan plan = apiPlanService.resolveForUser(apiKey.getUserId());
        QuotaDecision quota;
        try {
            quota = apiQuotaService.consume(apiKey.getUserId(), plan, request.getHeader("Idempotency-Key"), 1);
        } catch (Exception e) {
            DiagnosticRecorder.mark(request, e);
            return ResponseEntity.status(503).body(Map.of("error", "Usage metering is temporarily unavailable."));
        }
        if (!quota.isAllowed()) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "monthly".equals(quota.getReason()) ? "Monthly account quota exceeded." : "Daily account quota exceeded.",
                    "quota", quotaBody(plan, quota)));
        }
        apiKey.setLastUsedAt(new java.util.Date());
        apiKeyService.updateById(apiKey);
        String main = DomainUtils.getMainDomain(normalized);
        Domain domain = domainService.getOne(new QueryWrapper<Domain>().eq("NAME", main).eq("STATUS", Domain.STATUS_ACTIVE));
        if (domain == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Domain not found. Search it on the website first."));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", domain.getName()); data.put("registrar", domain.getRegistrar());
        data.put("createdAt", domain.getRegistCreateDateText()); data.put("updatedAt", domain.getRegistUpdateDateText());
        data.put("expiresAt", domain.getRegistExpiryDateText()); data.put("status", domain.getDomainStatus());
        data.put("nameServers", domain.getNameServerList()); data.put("dnssec", domain.getDnssec());
        data.put("whoisServer", domain.getFinalWhoisServer()); data.put("rdapServer", domain.getRdapServer());
        return ResponseEntity.ok(Map.of("data", data, "quota", quotaBody(plan, quota)));
    }

    private static Map<String, Object> quotaBody(ApiPlan plan, QuotaDecision quota) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", plan.getCode());
        body.put("dailyLimit", plan.getDailyRequestLimit());
        body.put("used", quota.getDailyUsed());
        body.put("monthlyLimit", plan.getMonthlyRequestLimit());
        body.put("monthlyUsed", quota.getMonthlyUsed());
        return body;
    }
}
