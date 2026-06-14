package info.wesite.web.controller.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainDns;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Related Domains Discovery API
 * 通过共享注册商、注册组织、Name Server、IP等维度发现关联域名
 */
@Tag(name = "Related Domains API")
@RestController
@RequestMapping("/api/tools")
public class RelatedDomainsController {

    private static final Logger log = LoggerFactory.getLogger(RelatedDomainsController.class);

    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";
    private static final int MAX_RESULTS_PER_CATEGORY = 50;
    /** 单个查询最长等待时间（秒） */
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private static final ExecutorService queryExecutor = Executors.newFixedThreadPool(10);

    @Autowired
    private DomainService domainService;

    @Autowired
    private DomainDnsService domainDnsService;

    @Operation(summary = "Discover domains related to a given domain")
    @GetMapping("/related/{domainName}")
    public ResponseJson<Map<String, Object>> findRelatedDomains(
            @PathVariable("domainName") String domainName,
            HttpServletRequest request) {

        String ip = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(ip, 5, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        domainName = domainName.toLowerCase().trim();
        String mainDomain = DomainUtils.getMainDomain(domainName);

        if (!mainDomain.matches(DOMAIN_REGEX)) {
            return ResponseJson.failure("Invalid domain name format.");
        }

        try {
            // 先获取目标域名信息
            Domain target = domainService.getOne(
                    Wrappers.<Domain>lambdaQuery().eq(Domain::getName, mainDomain));

            if (target == null || target.getStatus() != Domain.STATUS_ACTIVE) {
                return ResponseJson.failure("Domain not found in our database. Please search for it first on the homepage.");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("targetDomain", mainDomain);

            // ---- 并行执行所有查询 ----

            // 1. 同一注册组织
            CompletableFuture<Void> orgFuture = CompletableFuture.runAsync(() -> {
                if (StringUtils.isBlank(target.getRegistrantOrg())) return;
                try {
                    List<Domain> sameOrg = domainService.list(
                            Wrappers.<Domain>lambdaQuery()
                                    .eq(Domain::getStatus, Domain.STATUS_ACTIVE)
                                    .eq(Domain::getRegistrantOrg, target.getRegistrantOrg())
                                    .ne(Domain::getName, mainDomain)
                                    .orderByDesc(Domain::getUpdateTime)
                                    .last("LIMIT " + MAX_RESULTS_PER_CATEGORY));
                    synchronized (result) {
                        result.put("sameRegistrantOrg", buildDomainSummaryList(sameOrg));
                        result.put("registrantOrg", target.getRegistrantOrg());
                        result.put("sameRegistrantOrgCount", sameOrg.size());
                    }
                } catch (Exception e) {
                    log.warn("sameOrg query failed for {}: {}", mainDomain, e.getMessage());
                }
            }, queryExecutor);

            // 2. 同一注册商（跳过超大注册商避免全表扫描）
            CompletableFuture<Void> registrarFuture = CompletableFuture.runAsync(() -> {
                if (StringUtils.isBlank(target.getRegistrar())) return;
                try {
                    List<Domain> sameRegistrar = domainService.list(
                            Wrappers.<Domain>lambdaQuery()
                                    .eq(Domain::getStatus, Domain.STATUS_ACTIVE)
                                    .eq(Domain::getRegistrar, target.getRegistrar())
                                    .ne(Domain::getName, mainDomain)
                                    .orderByDesc(Domain::getUpdateTime)
                                    .last("LIMIT " + MAX_RESULTS_PER_CATEGORY));
                    synchronized (result) {
                        result.put("sameRegistrar", buildDomainSummaryList(sameRegistrar));
                        result.put("registrar", target.getRegistrar());
                        result.put("sameRegistrarCount", sameRegistrar.size());
                    }
                } catch (Exception e) {
                    log.warn("sameRegistrar query failed for {}: {}", mainDomain, e.getMessage());
                }
            }, queryExecutor);

            // 3. 同一Name Server
            CompletableFuture<Void> nsFuture = CompletableFuture.runAsync(() -> {
                if (StringUtils.isBlank(target.getNameServers())) return;
                List<String> nsList = target.getNameServerList();
                if (nsList.isEmpty()) return;
                String firstNs = nsList.get(0);
                try {
                    List<Domain> sameNs = domainService.list(
                            Wrappers.<Domain>lambdaQuery()
                                    .eq(Domain::getStatus, Domain.STATUS_ACTIVE)
                                    .like(Domain::getNameServers, firstNs)
                                    .ne(Domain::getName, mainDomain)
                                    .orderByDesc(Domain::getUpdateTime)
                                    .last("LIMIT " + MAX_RESULTS_PER_CATEGORY));
                    synchronized (result) {
                        result.put("sameNameServer", buildDomainSummaryList(sameNs));
                        result.put("nameServer", firstNs);
                        result.put("sameNameServerCount", sameNs.size());
                    }
                } catch (Exception e) {
                    log.warn("sameNameServer query failed for {}: {}", mainDomain, e.getMessage());
                }
            }, queryExecutor);

            // 4. 同一IP地址
            CompletableFuture<Void> ipFuture = CompletableFuture.runAsync(() -> {
                try {
                    DomainDns firstARecord = domainDnsService.getOne(
                            Wrappers.<DomainDns>lambdaQuery()
                                    .eq(DomainDns::getDomainId, target.getId())
                                    .eq(DomainDns::getStatus, DomainDns.STATUS_ACTIVE)
                                    .in(DomainDns::getType, DomainDns.TYPE_A, DomainDns.TYPE_AAAA)
                                    .last("LIMIT 1"));
                    if (firstARecord == null || StringUtils.isBlank(firstARecord.getValue())) return;
                    String firstIp = firstARecord.getValue().trim();

                    List<DomainDns> sameDnsRecords = domainDnsService.list(
                            Wrappers.<DomainDns>lambdaQuery()
                                    .eq(DomainDns::getStatus, DomainDns.STATUS_ACTIVE)
                                    .in(DomainDns::getType, DomainDns.TYPE_A, DomainDns.TYPE_AAAA)
                                    .eq(DomainDns::getValue, firstIp)
                                    .ne(DomainDns::getDomainId, target.getId())
                                    .last("LIMIT " + MAX_RESULTS_PER_CATEGORY));
                    List<String> sameIpDomainIds = sameDnsRecords.stream()
                            .map(DomainDns::getDomainId)
                            .distinct()
                            .collect(Collectors.toList());
                    if (sameIpDomainIds.isEmpty()) return;

                    List<Domain> sameIp = domainService.list(
                            Wrappers.<Domain>lambdaQuery()
                                    .eq(Domain::getStatus, Domain.STATUS_ACTIVE)
                                    .in(Domain::getId, sameIpDomainIds)
                                    .ne(Domain::getName, mainDomain)
                                    .orderByDesc(Domain::getUpdateTime)
                                    .last("LIMIT " + MAX_RESULTS_PER_CATEGORY));
                    synchronized (result) {
                        result.put("sameIp", buildDomainSummaryList(sameIp));
                        result.put("ipAddress", firstIp);
                        result.put("sameIpCount", sameIp.size());
                    }
                } catch (Exception e) {
                    log.warn("sameIp query failed for {}: {}", mainDomain, e.getMessage());
                }
            }, queryExecutor);

            // 5. 同一注册人邮箱
            CompletableFuture<Void> emailFuture = CompletableFuture.runAsync(() -> {
                String email = target.getRegistrantEmail();
                if (StringUtils.isBlank(email)) return;
                if (email.toLowerCase().contains("redacted") || email.toLowerCase().contains("privacy")) return;
                try {
                    List<Domain> sameEmail = domainService.list(
                            Wrappers.<Domain>lambdaQuery()
                                    .eq(Domain::getStatus, Domain.STATUS_ACTIVE)
                                    .eq(Domain::getRegistrantEmail, email)
                                    .ne(Domain::getName, mainDomain)
                                    .orderByDesc(Domain::getUpdateTime)
                                    .last("LIMIT " + MAX_RESULTS_PER_CATEGORY));
                    synchronized (result) {
                        result.put("sameRegistrantEmail", buildDomainSummaryList(sameEmail));
                        result.put("registrantEmail", email);
                        result.put("sameRegistrantEmailCount", sameEmail.size());
                    }
                } catch (Exception e) {
                    log.warn("sameEmail query failed for {}: {}", mainDomain, e.getMessage());
                }
            }, queryExecutor);

            // 等待所有查询完成，超时则忽略未完成的（已完成的部分结果照常返回）
            try {
                CompletableFuture.allOf(orgFuture, registrarFuture, nsFuture, ipFuture, emailFuture)
                        .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Some related-domain queries timed out for: {}, returning partial results", mainDomain);
            }

            // 汇总 totalRelated
            int totalRelated = 0;
            for (String key : new String[]{"sameRegistrantOrgCount", "sameRegistrarCount",
                    "sameNameServerCount", "sameIpCount", "sameRegistrantEmailCount"}) {
                Object v = result.get(key);
                if (v instanceof Integer) totalRelated += (Integer) v;
            }
            result.put("totalRelated", totalRelated);

            RateLimitUtils.incrementRequestCount(ip);
            return ResponseJson.success(result);

        } catch (Exception e) {
            log.error("Error finding related domains for: {}", mainDomain, e);
            return ResponseJson.failure("Failed to find related domains: " + e.getMessage());
        }
    }

    /**
     * 将Domain列表转换为前端需要的摘要格式
     */
    private List<Map<String, Object>> buildDomainSummaryList(List<Domain> domains) {
        if (domains == null || domains.isEmpty()) {
            return new ArrayList<>();
        }
        return domains.stream().map(d -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", d.getName());
            summary.put("registrar", d.getRegistrar());
            summary.put("expiryDate", d.getRegistExpiryDateText());
            summary.put("creationDate", d.getRegistCreateDateText());
            summary.put("registrantOrg", d.getRegistrantOrg());
            summary.put("registrantCountry", d.getRegistrantCountry());
            summary.put("nameServers", d.getNameServers());
            return summary;
        }).collect(Collectors.toList());
    }
}
