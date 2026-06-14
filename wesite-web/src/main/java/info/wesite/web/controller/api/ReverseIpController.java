package info.wesite.web.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

import info.wesite.core.entity.Domain;
import info.wesite.core.handler.Geoip2Handler;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Reverse IP Lookup API
 * 通过IP地址查找所有托管在该IP上的域名
 */
@Tag(name = "Reverse IP Lookup API")
@RestController
@RequestMapping("/api/tools")
public class ReverseIpController {

    private static final Logger log = LoggerFactory.getLogger(ReverseIpController.class);

    private static final String IPV4_REGEX = "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$";

    @Autowired
    private DomainService domainService;

    @Autowired
    private DomainDnsService domainDnsService;

//    @Autowired
//    private IpAddressService ipAddressService;

    @Autowired(required = false)
    private Geoip2Handler geoip2Handler;

    @Operation(summary = "Find all domains hosted on a given IP address")
    @GetMapping("/reverse-ip/{ip}")
    public ResponseJson<Map<String, Object>> reverseIpLookup(
            @PathVariable("ip") String ip,
            @RequestParam(value = "page", defaultValue = "1") int page,
            HttpServletRequest request) {

        String clientIp = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(clientIp, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        if (StringUtils.isBlank(ip)) {
            return ResponseJson.failure("IP address is required.");
        }

        ip = ip.trim();
        if (!ip.matches(IPV4_REGEX)) {
            return ResponseJson.failure("Invalid IPv4 address format.");
        }

        if (page < 1) page = 1;
        int pageSize = 50;

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ip", ip);

            // IP地理位置信息
            if (geoip2Handler != null) {
                try {
                    String cityJson = geoip2Handler.getCityJson(ip);
                    String asnJson = geoip2Handler.getAsnJson(ip);
                    if (StringUtils.isNotBlank(cityJson)) {
                        result.put("geoInfo", com.alibaba.fastjson2.JSON.parseObject(cityJson));
                    }
                    if (StringUtils.isNotBlank(asnJson)) {
                        result.put("asnInfo", com.alibaba.fastjson2.JSON.parseObject(asnJson));
                    }
                } catch (Exception e) {
                    log.warn("GeoIP lookup failed for {}: {}", ip, e.getMessage());
                }
            }

//            // IP地址记录
//            IpAddress ipAddr = ipAddressService.getOne(new QueryWrapper<IpAddress>().eq("IP", ip));
//            if (ipAddr != null) {
//                Map<String, Object> ipInfo = new LinkedHashMap<>();
//                ipInfo.put("ip", ipAddr.getIp());
//                result.put("ipRecord", ipInfo);
//            }

            // 查找托管在该IP上的域名
            // 1. 先通过数据库分页直接拿本页所需的 domainId（只查当页，不全量加载）
            long totalCount = domainDnsService.countDomainIdsByIp(ip);
            long totalPages = totalCount == 0 ? 0 : (totalCount + pageSize - 1) / pageSize;

            List<Map<String, Object>> domains = new ArrayList<>();

            if (totalCount > 0) {
                List<String> pagedomainIds = domainDnsService.listDomainIdsByIp(ip, page, pageSize);
                if (!pagedomainIds.isEmpty()) {
                    List<Domain> domainList = domainService.list(
                            Wrappers.<Domain>lambdaQuery()
                                    .eq(Domain::getStatus, Domain.STATUS_ACTIVE)
                                    .in(Domain::getId, pagedomainIds)
                                    .orderByDesc(Domain::getUpdateTime));
                    for (Domain d : domainList) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", d.getName());
                        item.put("registrar", d.getRegistrar());
                        item.put("expiryDate", d.getRegistExpiryDateText());
                        item.put("registrantOrg", d.getRegistrantOrg());
                        item.put("nameServers", d.getNameServers());
                        domains.add(item);
                    }
                }
            }

            result.put("domains", domains);
            result.put("total", totalCount);
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("totalPages", totalPages);

            RateLimitUtils.incrementRequestCount(clientIp);
            return ResponseJson.success(result);
        } catch (Exception e) {
            log.error("Error performing reverse IP lookup for: {}", ip, e);
            return ResponseJson.failure("Reverse IP lookup failed: " + e.getMessage());
        }
    }
}
