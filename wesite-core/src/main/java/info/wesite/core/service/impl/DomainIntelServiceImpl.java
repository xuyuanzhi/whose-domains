package info.wesite.core.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.model.DomainIntelResult;
import info.wesite.core.model.RegistrationStatus;
import info.wesite.core.service.DomainIntelService;
import info.wesite.core.service.DomainProbe;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.utils.DomainNameValidator;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.RdapUtils;
import info.wesite.core.utils.WhoisUtils;

/**
 * Default {@link DomainIntelService}. Reuses the persisted domain row, the per-TLD RDAP/WHOIS
 * servers and the existing parsers; it adds the missing status taxonomy and error provenance.
 *
 * <p>Resolution order and, importantly, what each outcome may conclude:</p>
 * <ol>
 *   <li>Local database row ({@code WEB_DOMAIN}) — authoritative for what we already know.</li>
 *   <li>RDAP: 2xx = registered; 404 = no record (candidate, not "available"); anything else = error.</li>
 *   <li>WHOIS: registered text = registered; "no match" text = no record; blank/throw = error.</li>
 *   <li>Only when RDAP and/or WHOIS positively report no record do we emit
 *       {@code unregistered_candidate}. DNS is used solely as a last-resort registered signal and
 *       never to claim availability.</li>
 * </ol>
 */
@Service
public class DomainIntelServiceImpl implements DomainIntelService {

    private static final Logger log = LoggerFactory.getLogger(DomainIntelServiceImpl.class);

    private final DomainService domainService;
    private final DomainTldService domainTldService;
    private final DomainProbe probe;

    public DomainIntelServiceImpl(DomainService domainService, DomainTldService domainTldService, DomainProbe probe) {
        this.domainService = domainService;
        this.domainTldService = domainTldService;
        this.probe = probe;
    }

    @Override
    public DomainIntelResult check(String domain) {
        DomainIntelResult result = new DomainIntelResult();
        result.setCheckedAt(Instant.now().toString());

        String normalized = DomainNameValidator.normalize(domain);
        if (normalized == null || !DomainNameValidator.isValid(normalized)) {
            result.setDomain(domain == null ? null : domain.trim());
            result.setStatus(RegistrationStatus.UNKNOWN);
            result.setSource("none");
            result.addError("invalid domain name");
            return result;
        }

        String main = DomainUtils.getMainDomain(normalized);
        if (StringUtils.isBlank(main)) {
            main = normalized;
        }
        result.setDomain(main);

        try {
            return resolve(main, result);
        } catch (RuntimeException e) {
            log.warn("Domain intelligence lookup failed for {}", main, e);
            result.setStatus(RegistrationStatus.UNKNOWN);
            result.setSource("none");
            result.addError("lookup failed: " + e.getClass().getSimpleName());
            return result;
        }
    }

    private DomainIntelResult resolve(String main, DomainIntelResult result) {
        Domain stored = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, main));
        if (stored != null && stored.getStatus() != null && stored.getStatus() == Domain.STATUS_ACTIVE) {
            applyDomain(result, stored);
            result.setStatus(classify(stored.getDomainStatus(), RegistrationStatus.REGISTERED));
            result.setSource("database");
            result.setCached(true);
            return result;
        }

        String tldName = DomainUtils.getTldName(main);
        DomainTld tld = tldName == null ? null
                : domainTldService.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getDisplayName, tldName));

        boolean rdapNotFound = false;
        boolean whoisNoMatch = false;
        boolean whoisAttempted = false;

        if (tld != null && StringUtils.isNotBlank(tld.getRdapServer())) {
            result.setRdapServer(tld.getRdapServer());
            RdapUtils.RdapResponse response = probe.rdap(main, tld.getRdapServer());
            if (response.outcome == RdapUtils.RdapOutcome.OK) {
                Domain parsed = new Domain();
                parsed.setName(main);
                try {
                    RdapUtils.fillRdapInfoFromText(parsed, response.body);
                } catch (RuntimeException e) {
                    result.addError("rdap: parse failed");
                }
                applyDomain(result, parsed);
                result.setStatus(classify(parsed.getDomainStatus(), RegistrationStatus.REGISTERED));
                result.setSource("rdap");
                return result;
            } else if (response.outcome == RdapUtils.RdapOutcome.NOT_FOUND) {
                rdapNotFound = true;
            } else {
                result.addError("rdap: " + response.message);
            }
        }

        if (tld != null && StringUtils.isNotBlank(tld.getWhoisServer())) {
            result.setWhoisServer(tld.getWhoisServer());
            whoisAttempted = true;
            DomainProbe.WhoisResult whois = probe.whois(main, tld.getWhoisServer());
            if (!whois.isOk()) {
                result.addError("whois: " + whois.getError());
            } else if (StringUtils.isBlank(whois.getText())) {
                result.addError("whois: empty response");
            } else if (WhoisUtils.isValid(whois.getText())) {
                Domain parsed = new Domain();
                parsed.setName(main);
                try {
                    WhoisUtils.fillWhoisInfoFromText(parsed, whois.getText());
                } catch (RuntimeException e) {
                    result.addError("whois: parse failed");
                }
                applyDomain(result, parsed);
                result.setStatus(classify(parsed.getDomainStatus(), RegistrationStatus.REGISTERED));
                result.setSource("whois");
                return result;
            } else {
                whoisNoMatch = true;
            }
        }

        if (rdapNotFound || whoisNoMatch) {
            result.setStatus(RegistrationStatus.UNREGISTERED_CANDIDATE);
            result.setSource(rdapNotFound && whoisNoMatch ? "rdap+whois" : (rdapNotFound ? "rdap" : "whois"));
            return result;
        }

        // No registry source produced a conclusion. DNS is a weak "someone uses it" signal: it may
        // upgrade the result to registered, but its absence must not downgrade to available.
        if (probe.dnsEnabled(main)) {
            result.setStatus(RegistrationStatus.REGISTERED);
            result.setSource("dns");
            result.addError("registration inferred from DNS only; registry data unavailable");
            return result;
        }

        if (tld == null) {
            result.addError("no registry source configured for tld");
        } else if (!whoisAttempted && StringUtils.isBlank(tld.getRdapServer())) {
            result.addError("no rdap/whois server configured for tld");
        }
        result.setStatus(RegistrationStatus.UNKNOWN);
        if ("database".equals(result.getSource()) || result.getSource() == null) {
            result.setSource("none");
        }
        return result;
    }

    private static RegistrationStatus classify(String registryStatuses, RegistrationStatus fallback) {
        if (registryStatuses != null) {
            String value = registryStatuses.toLowerCase(Locale.ROOT);
            // Note: EPP "client/server*Prohibited" statuses are ordinary registration locks, not
            // reserved names, so we must not match a bare "prohibited".
            if (value.contains("reserved") || value.contains("not available")
                    || value.contains("blocked") || value.contains("prohibited from registration")
                    || value.contains("prohibited registration")) {
                return RegistrationStatus.RESERVED;
            }
        }
        return fallback;
    }

    private static void applyDomain(DomainIntelResult result, Domain domain) {
        result.setRegistrar(domain.getRegistrar());
        result.setCreatedAt(domain.getRegistCreateDateText());
        result.setUpdatedAt(domain.getRegistUpdateDateText());
        result.setExpiresAt(domain.getRegistExpiryDateText());
        result.setRegistryStatuses(splitCsv(domain.getDomainStatus()));
        result.setNameServers(splitCsv(domain.getNameServers()));
        result.setDnssec(domain.getDnssec());
        if (StringUtils.isNotBlank(domain.getWhoisServer())) {
            result.setWhoisServer(domain.getWhoisServer());
        }
        if (StringUtils.isNotBlank(domain.getRdapServer())) {
            result.setRdapServer(domain.getRdapServer());
        }
    }

    private static List<String> splitCsv(String value) {
        List<String> values = new ArrayList<>();
        if (StringUtils.isBlank(value)) {
            return values;
        }
        for (String part : Arrays.asList(value.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
