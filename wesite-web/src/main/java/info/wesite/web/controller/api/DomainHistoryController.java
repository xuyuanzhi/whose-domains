package info.wesite.web.controller.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.DomainSnapshot;
import info.wesite.core.service.DomainSnapshotService;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 域名WHOIS历史快照 & Diff对比 API
 */
@Tag(name = "Domain History API - WHOIS历史快照")
@RestController
@RequestMapping("/api/domain-history")
public class DomainHistoryController {

    private static final Logger log = LoggerFactory.getLogger(DomainHistoryController.class);

    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";

    @Autowired
    private DomainSnapshotService domainSnapshotService;

    /**
     * 获取指定域名的所有WHOIS历史快照列表（不含原始文本，仅摘要）
     */
    @Operation(summary = "获取域名WHOIS快照列表")
    @GetMapping("/snapshots/{domainName}")
    public ResponseJson<Map<String, Object>> listSnapshots(
            @PathVariable("domainName") String domainName,
            HttpServletRequest request) {

        String ip = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        domainName = domainName.toLowerCase().trim();
        if (!domainName.matches(DOMAIN_REGEX)) {
            return ResponseJson.failure("Invalid domain name format.");
        }

        List<DomainSnapshot> snapshots = domainSnapshotService.list(
                Wrappers.<DomainSnapshot>lambdaQuery()
                        .eq(DomainSnapshot::getDomainName, domainName)
                        .eq(DomainSnapshot::getStatus, DomainSnapshot.STATUS_ACTIVE)
                        .orderByDesc(DomainSnapshot::getSnapshotTime)
                        .last("LIMIT 100"));

        // 返回摘要信息（不包含大文本字段）
        List<Map<String, Object>> summaryList = new ArrayList<>();
        for (DomainSnapshot s : snapshots) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", s.getId());
            summary.put("domainName", s.getDomainName());
            summary.put("registrar", s.getRegistrar());
            summary.put("expiryDate", s.getRegistExpiryDateText());
            summary.put("updateDate", s.getRegistUpdateDateText());
            summary.put("nameServers", s.getNameServers());
            summary.put("dnssec", s.getDnssec());
            summary.put("registrantOrg", s.getRegistrantOrg());
            summary.put("registrantCountry", s.getRegistrantCountry());
            summary.put("snapshotTime", s.getCreateTimeText());
            summaryList.add(summary);
        }

        RateLimitUtils.incrementRequestCount(ip);
        return ResponseJson.success(summaryList);
    }

    /**
     * 获取单条快照的完整详情（包含原始文本）
     */
    @Operation(summary = "获取快照详情")
    @GetMapping("/snapshot/{id}")
    public ResponseJson<DomainSnapshot> getSnapshot(@PathVariable("id") String id) {
        DomainSnapshot snapshot = domainSnapshotService.getById(id);
        if (snapshot == null || snapshot.getStatus() != DomainSnapshot.STATUS_ACTIVE) {
            return ResponseJson.failure("Snapshot not found.");
        }
        return ResponseJson.success(snapshot);
    }

    /**
     * 对比两个快照的差异
     * 
     * @param id1 旧快照ID
     * @param id2 新快照ID
     */
    @Operation(summary = "对比两个WHOIS快照的差异")
    @GetMapping("/diff")
    public ResponseJson<Map<String, Object>> diffSnapshots(
            @RequestParam("id1") String id1,
            @RequestParam("id2") String id2,
            HttpServletRequest request) {

        String ip = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        if (StringUtils.isBlank(id1) || StringUtils.isBlank(id2)) {
            return ResponseJson.failure("Both snapshot IDs are required.");
        }

        DomainSnapshot older = domainSnapshotService.getById(id1);
        DomainSnapshot newer = domainSnapshotService.getById(id2);

        if (older == null || newer == null) {
            return ResponseJson.failure("One or both snapshots not found.");
        }

        if (!older.getDomainName().equals(newer.getDomainName())) {
            return ResponseJson.failure("Snapshots must belong to the same domain.");
        }

        // 确保 older 确实更旧
        if (older.getSnapshotTime() != null && newer.getSnapshotTime() != null
                && older.getSnapshotTime().after(newer.getSnapshotTime())) {
            DomainSnapshot tmp = older;
            older = newer;
            newer = tmp;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("domainName", older.getDomainName());
        result.put("olderSnapshot", buildSnapshotSummary(older));
        result.put("newerSnapshot", buildSnapshotSummary(newer));

        // 逐字段对比
        List<Map<String, Object>> changes = new ArrayList<>();

        addDiff(changes, "Registrar", older.getRegistrar(), newer.getRegistrar());
        addDiff(changes, "Registrar URL", older.getRegistrarUrl(), newer.getRegistrarUrl());
        addDiff(changes, "Registration Date", older.getRegistCreateDateText(), newer.getRegistCreateDateText());
        addDiff(changes, "Last Updated Date", older.getRegistUpdateDateText(), newer.getRegistUpdateDateText());
        addDiff(changes, "Expiry Date", older.getRegistExpiryDateText(), newer.getRegistExpiryDateText());
        addDiff(changes, "Domain Status", older.getDomainStatus(), newer.getDomainStatus());
        addDiff(changes, "Name Servers", older.getNameServers(), newer.getNameServers());
        addDiff(changes, "DNSSEC", older.getDnssec(), newer.getDnssec());
        addDiff(changes, "Registrant Organization", older.getRegistrantOrg(), newer.getRegistrantOrg());
        addDiff(changes, "Registrant Name", older.getRegistrantName(), newer.getRegistrantName());
        addDiff(changes, "Registrant Country", older.getRegistrantCountry(), newer.getRegistrantCountry());
        addDiff(changes, "Registrant State", older.getRegistrantState(), newer.getRegistrantState());
        addDiff(changes, "Registrant City", older.getRegistrantCity(), newer.getRegistrantCity());
        addDiff(changes, "Registrant Email", older.getRegistrantEmail(), newer.getRegistrantEmail());
        addDiff(changes, "Tech Name", older.getTechName(), newer.getTechName());
        addDiff(changes, "Tech Email", older.getTechEmail(), newer.getTechEmail());

        result.put("changes", changes);
        result.put("totalChanges", changes.size());
        result.put("hasChanges", !changes.isEmpty());

        RateLimitUtils.incrementRequestCount(ip);
        return ResponseJson.success(result);
    }

    /**
     * 获取域名最近两次快照的自动对比（快捷方法）
     */
    @Operation(summary = "获取域名最新的变更对比")
    @GetMapping("/latest-diff/{domainName}")
    public ResponseJson<Map<String, Object>> latestDiff(
            @PathVariable("domainName") String domainName,
            HttpServletRequest request) {

        domainName = domainName.toLowerCase().trim();
        if (!domainName.matches(DOMAIN_REGEX)) {
            return ResponseJson.failure("Invalid domain name format.");
        }

        List<DomainSnapshot> latest = domainSnapshotService.list(
                Wrappers.<DomainSnapshot>lambdaQuery()
                        .eq(DomainSnapshot::getDomainName, domainName)
                        .eq(DomainSnapshot::getStatus, DomainSnapshot.STATUS_ACTIVE)
                        .orderByDesc(DomainSnapshot::getSnapshotTime)
                        .last("LIMIT 2"));

        if (latest == null || latest.size() < 2) {
            return ResponseJson.failure("Not enough snapshots for comparison. At least 2 are needed.");
        }

        // latest.get(0) is newest, latest.get(1) is older
        DomainSnapshot newer = latest.get(0);
        DomainSnapshot older = latest.get(1);

        Map<String, Object> result = new HashMap<>();
        result.put("domainName", domainName);
        result.put("olderSnapshot", buildSnapshotSummary(older));
        result.put("newerSnapshot", buildSnapshotSummary(newer));

        List<Map<String, Object>> changes = new ArrayList<>();
        addDiff(changes, "Registrar", older.getRegistrar(), newer.getRegistrar());
        addDiff(changes, "Registrar URL", older.getRegistrarUrl(), newer.getRegistrarUrl());
        addDiff(changes, "Registration Date", older.getRegistCreateDateText(), newer.getRegistCreateDateText());
        addDiff(changes, "Last Updated Date", older.getRegistUpdateDateText(), newer.getRegistUpdateDateText());
        addDiff(changes, "Expiry Date", older.getRegistExpiryDateText(), newer.getRegistExpiryDateText());
        addDiff(changes, "Domain Status", older.getDomainStatus(), newer.getDomainStatus());
        addDiff(changes, "Name Servers", older.getNameServers(), newer.getNameServers());
        addDiff(changes, "DNSSEC", older.getDnssec(), newer.getDnssec());
        addDiff(changes, "Registrant Organization", older.getRegistrantOrg(), newer.getRegistrantOrg());
        addDiff(changes, "Registrant Name", older.getRegistrantName(), newer.getRegistrantName());
        addDiff(changes, "Registrant Country", older.getRegistrantCountry(), newer.getRegistrantCountry());
        addDiff(changes, "Registrant State", older.getRegistrantState(), newer.getRegistrantState());
        addDiff(changes, "Registrant City", older.getRegistrantCity(), newer.getRegistrantCity());
        addDiff(changes, "Registrant Email", older.getRegistrantEmail(), newer.getRegistrantEmail());
        addDiff(changes, "Tech Name", older.getTechName(), newer.getTechName());
        addDiff(changes, "Tech Email", older.getTechEmail(), newer.getTechEmail());

        result.put("changes", changes);
        result.put("totalChanges", changes.size());
        result.put("hasChanges", !changes.isEmpty());

        return ResponseJson.success(result);
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> buildSnapshotSummary(DomainSnapshot s) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", s.getId());
        summary.put("snapshotTime", s.getCreateTimeText());
        summary.put("registrar", s.getRegistrar());
        summary.put("expiryDate", s.getRegistExpiryDateText());
        summary.put("nameServers", s.getNameServers());
        return summary;
    }

    /**
     * 比较两个字段值，如有变化则添加到changes列表
     */
    private void addDiff(List<Map<String, Object>> changes, String field, String oldVal, String newVal) {
        // 标准化：null 和空字符串视为等同
        String normalizedOld = StringUtils.isBlank(oldVal) ? null : oldVal.trim();
        String normalizedNew = StringUtils.isBlank(newVal) ? null : newVal.trim();

        if (!Objects.equals(normalizedOld, normalizedNew)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("field", field);
            change.put("oldValue", normalizedOld != null ? normalizedOld : "(empty)");
            change.put("newValue", normalizedNew != null ? normalizedNew : "(empty)");

            // 分类变更类型
            if (normalizedOld == null && normalizedNew != null) {
                change.put("type", "added");
            } else if (normalizedOld != null && normalizedNew == null) {
                change.put("type", "removed");
            } else {
                change.put("type", "modified");
            }

            changes.add(change);
        }
    }
}
