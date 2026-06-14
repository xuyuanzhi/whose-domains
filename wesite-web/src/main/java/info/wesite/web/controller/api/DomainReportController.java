package info.wesite.web.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainDns;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.entity.DomainSnapshot;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainSiteService;
import info.wesite.core.service.DomainSnapshotService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Domain Report Export API
 * 生成可下载的 JSON 格式综合域名报告
 */
@Tag(name = "Domain Report API")
@RestController
@RequestMapping("/api")
public class DomainReportController {

    private static final Logger log = LoggerFactory.getLogger(DomainReportController.class);

    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";

    @Autowired
    private DomainService domainService;

    @Autowired
    private DomainDnsService domainDnsService;

    @Autowired
    private DomainSiteService domainSiteService;

    @Autowired
    private DomainSnapshotService domainSnapshotService;

    /**
     * 生成并下载域名综合报告 (JSON格式)
     */
    @Operation(summary = "Export comprehensive domain report as JSON")
    @GetMapping(value = "/domain-report/{domainName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportDomainReport(
            @PathVariable("domainName") String domainName,
            HttpServletRequest request) {

        String ip = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(ip, 5, 60000)) {
            return ResponseEntity.status(429).body("{\"error\":\"Too many requests. Please try again later.\"}");
        }

        domainName = domainName.toLowerCase().trim();
        String mainDomain = DomainUtils.getMainDomain(domainName);

        if (!mainDomain.matches(DOMAIN_REGEX)) {
            return ResponseEntity.badRequest().body("{\"error\":\"Invalid domain name format.\"}");
        }

        Domain domain = domainService.getOne(
                Wrappers.<Domain>lambdaQuery().eq(Domain::getName, mainDomain));

        if (domain == null || domain.getStatus() != Domain.STATUS_ACTIVE) {
            return ResponseEntity.status(404).body("{\"error\":\"Domain not found. Please search for it first.\"}");
        }

        try {
            Map<String, Object> report = new LinkedHashMap<>();

            // Meta
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("generatedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new java.util.Date()));
            meta.put("source", "https://whose.domains");
            meta.put("version", "1.0");
            report.put("meta", meta);

            // WHOIS / Registration
            Map<String, Object> whois = new LinkedHashMap<>();
            whois.put("domainName", domain.getName());
            whois.put("registryDomainId", domain.getRegistryDomainID());
            whois.put("registrar", domain.getRegistrar());
            whois.put("registrarIanaId", domain.getRegistrarIanaID());
            whois.put("registrarUrl", domain.getRegistrarUrl());
            whois.put("creationDate", domain.getRegistCreateDateText());
            whois.put("updateDate", domain.getRegistUpdateDateText());
            whois.put("expiryDate", domain.getRegistExpiryDateText());
            whois.put("domainStatus", domain.getDomainStatus());
            whois.put("nameServers", domain.getNameServerList());
            whois.put("dnssec", domain.getDnssec());
            whois.put("whoisServer", domain.getFinalWhoisServer());
            whois.put("rdapServer", domain.getRdapServer());
            report.put("whois", whois);

            // Registrant
            Map<String, Object> registrant = new LinkedHashMap<>();
            registrant.put("organization", domain.getRegistrantOrg());
            registrant.put("name", domain.getRegistrantName());
            registrant.put("city", domain.getRegistrantCity());
            registrant.put("state", domain.getRegistrantState());
            registrant.put("country", domain.getRegistrantCountry());
            registrant.put("phone", domain.getRegistrantPhone());
            registrant.put("email", domain.getRegistrantEmail());
            report.put("registrant", registrant);

            // Tech contact
            Map<String, Object> tech = new LinkedHashMap<>();
            tech.put("name", domain.getTechName());
            tech.put("phone", domain.getTechPhone());
            tech.put("email", domain.getTechEmail());
            report.put("techContact", tech);

            // DNS Records
            List<DomainDns> dnsRecords = domainDnsService.list(
                    Wrappers.<DomainDns>lambdaQuery()
                            .eq(DomainDns::getDomainId, domain.getId())
                            .eq(DomainDns::getStatus, DomainDns.STATUS_ACTIVE)
                            .orderByAsc(DomainDns::getType));

            List<Map<String, Object>> dnsItems = new ArrayList<>();
            if (dnsRecords != null) {
                for (DomainDns dns : dnsRecords) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", dns.getType());
                    item.put("name", dns.getName());
                    item.put("value", dns.getValue());
                    item.put("ttl", dns.getTtl());
                    dnsItems.add(item);
                }
            }
            report.put("dnsRecords", dnsItems);

            // Subdomains
            List<DomainSite> sites = domainSiteService.list(
                    Wrappers.<DomainSite>lambdaQuery()
                            .eq(DomainSite::getDomainId, domain.getId())
                            .eq(DomainSite::getStatus, DomainSite.STATUS_ACTIVE)
                            .orderByAsc(DomainSite::getName));

            List<Map<String, Object>> subdomains = new ArrayList<>();
            if (sites != null) {
                for (DomainSite site : sites) {
                    Map<String, Object> sub = new LinkedHashMap<>();
                    sub.put("name", site.getName());
                    sub.put("homePageTitle", site.getHomePageTitle());
                    sub.put("serverName", site.getServerName());
                    subdomains.add(sub);
                }
            }
            report.put("subdomains", subdomains);

            // History (last 10 snapshots summary)
            List<DomainSnapshot> snapshots = domainSnapshotService.list(
                    Wrappers.<DomainSnapshot>lambdaQuery()
                            .eq(DomainSnapshot::getDomainName, mainDomain)
                            .eq(DomainSnapshot::getStatus, DomainSnapshot.STATUS_ACTIVE)
                            .orderByDesc(DomainSnapshot::getSnapshotTime)
                            .last("LIMIT 10"));

            List<Map<String, Object>> history = new ArrayList<>();
            if (snapshots != null) {
                for (DomainSnapshot s : snapshots) {
                    Map<String, Object> snap = new LinkedHashMap<>();
                    snap.put("snapshotTime", s.getCreateTimeText());
                    snap.put("registrar", s.getRegistrar());
                    snap.put("expiryDate", s.getRegistExpiryDateText());
                    snap.put("nameServers", s.getNameServers());
                    snap.put("registrantOrg", s.getRegistrantOrg());
                    history.add(snap);
                }
            }
            report.put("whoisHistory", history);

            // IP addresses (从web_domain_dns表的A/AAAA记录查询)
            List<DomainDns> ipRecords = domainDnsService.list(
                    Wrappers.<DomainDns>lambdaQuery()
                            .eq(DomainDns::getDomainId, domain.getId())
                            .eq(DomainDns::getStatus, DomainDns.STATUS_ACTIVE)
                            .in(DomainDns::getType, DomainDns.TYPE_A, DomainDns.TYPE_AAAA));
            if (ipRecords != null && !ipRecords.isEmpty()) {
                report.put("ipAddresses", ipRecords.stream()
                        .map(DomainDns::getValue)
                        .toArray(String[]::new));
            }

            String jsonStr = JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat);

            RateLimitUtils.incrementRequestCount(ip);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + mainDomain + "-report.json\"");

            return ResponseEntity.ok().headers(headers).body(jsonStr);
        } catch (Exception e) {
            log.error("Error generating report for domain: {}", mainDomain, e);
            return ResponseEntity.internalServerError().body("{\"error\":\"Failed to generate report.\"}");
        }
    }
}
