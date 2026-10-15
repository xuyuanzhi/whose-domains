package info.wesite.core.service.impl;

import org.springframework.stereotype.Component;

import info.wesite.core.service.DomainProbe;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.RdapUtils;
import info.wesite.core.utils.WhoisUtils;

/**
 * Production {@link DomainProbe} that delegates to the existing static WHOIS/RDAP/DNS helpers,
 * so the MCP tools and the website share exactly the same lookup implementation.
 */
@Component
public class StaticDomainProbe implements DomainProbe {

    @Override
    public RdapUtils.RdapResponse rdap(String domain, String server) {
        return RdapUtils.fetch(domain, server);
    }

    @Override
    public WhoisResult whois(String domain, String server) {
        try {
            return WhoisResult.ok(WhoisUtils.getWhoisText(domain, server));
        } catch (Exception e) {
            return WhoisResult.failed(e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean dnsEnabled(String domain) {
        return DomainUtils.isDnsEnabled(domain);
    }
}
