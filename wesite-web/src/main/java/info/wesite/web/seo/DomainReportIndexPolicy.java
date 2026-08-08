package info.wesite.web.seo;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainDns;

@Component
public class DomainReportIndexPolicy {

    private static final String INDEX_ROBOTS =
            "index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1";
    private static final String NOINDEX_ROBOTS = "noindex, follow";

    public boolean isIndexable(Domain domain, List<DomainDns> dnsRecords) {
        if (domain == null || StringUtils.isBlank(domain.getName())) {
            return false;
        }

        int evidenceGroups = 0;
        if (StringUtils.isNotBlank(domain.getRegistryDomainID())
                || StringUtils.isNotBlank(domain.getRegistrar())) {
            evidenceGroups++;
        }
        if (StringUtils.isNotBlank(domain.getRegistCreateDateText())
                || StringUtils.isNotBlank(domain.getRegistExpiryDateText())) {
            evidenceGroups++;
        }
        if (StringUtils.isNotBlank(domain.getNameServers())
                || (dnsRecords != null && !dnsRecords.isEmpty())) {
            evidenceGroups++;
        }
        if (StringUtils.isNotBlank(domain.getFinalWhoisText())
                || StringUtils.isNotBlank(domain.getRdapText())
                || StringUtils.isNotBlank(domain.getParentRdapText())) {
            evidenceGroups++;
        }
        return evidenceGroups >= 2;
    }

    public String robotsDirective(Domain domain, List<DomainDns> dnsRecords) {
        return isIndexable(domain, dnsRecords) ? INDEX_ROBOTS : NOINDEX_ROBOTS;
    }
}
