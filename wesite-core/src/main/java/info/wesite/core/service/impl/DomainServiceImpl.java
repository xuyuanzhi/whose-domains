package info.wesite.core.service.impl;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.Domain;
import info.wesite.core.mapper.DomainMapper;
import info.wesite.core.service.DomainService;

@Service
public class DomainServiceImpl extends ServiceImpl<DomainMapper, Domain> implements DomainService {

    protected static Logger logger = LoggerFactory.getLogger(DomainServiceImpl.class);

    @Override
    public List<Domain> listExpiringDomains(String nowStr, String futureStr, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return getBaseMapper().selectExpiringDomains(nowStr, futureStr, pageSize, offset);
    }

    @Override
    public long countExpiringDomains(String nowStr, String futureStr) {
        return getBaseMapper().countExpiringDomains(nowStr, futureStr);
    }

    @Override
    public Map<Integer, Map<String, Object>> buildExpiringDomainsCache(int days) {
        Date now = new Date();
        Date futureDate = DateUtils.addDays(now, days);
        String nowStr = new java.text.SimpleDateFormat("yyyy-MM-dd").format(now);
        String futureStr = new java.text.SimpleDateFormat("yyyy-MM-dd").format(futureDate);

        int pageSize = 50;
        long total = countExpiringDomains(nowStr, futureStr);
        int totalPages = (int) Math.ceil((double) total / pageSize);

        Map<Integer, Map<String, Object>> allPages = new LinkedHashMap<>();

        for (int p = 1; p <= totalPages; p++) {
            List<Domain> records = listExpiringDomains(nowStr, futureStr, p, pageSize);
            List<Map<String, Object>> items = toItemList(records, now);

            Map<String, Object> pageData = new LinkedHashMap<>();
            pageData.put("domains", items);
            pageData.put("total", total);
            pageData.put("page", p);
            pageData.put("pageSize", pageSize);
            pageData.put("totalPages", totalPages);
            pageData.put("days", days);
            allPages.put(p, pageData);
        }

        return allPages;
    }

    private List<Map<String, Object>> toItemList(List<Domain> records, Date now) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (records == null) return items;
        for (Domain d : records) {
            Map<String, Object> item = new LinkedHashMap<>();
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
        return items;
    }
}
