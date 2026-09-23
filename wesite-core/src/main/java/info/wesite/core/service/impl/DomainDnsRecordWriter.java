package info.wesite.core.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import info.wesite.core.entity.DomainDns;
import info.wesite.core.handler.Geoip2Handler;
import info.wesite.core.mapper.DomainDnsMapper;

/** Shared persistence path for apex and subdomain DNS refreshes. */
@Service
public class DomainDnsRecordWriter {
    private final DomainDnsMapper mapper;
    private final Geoip2Handler geo;

    public DomainDnsRecordWriter(DomainDnsMapper mapper, Geoip2Handler geo) {
        this.mapper = mapper;
        this.geo = geo;
    }

    @Transactional
    public void saveObserved(String domainId, Record record) {
        Objects.requireNonNull(domainId, "domainId");
        String name = record.getName().toString();
        String type = Type.string(record.getType());
        String value = record.rdataToString();
        DomainDns existing = one(mapper.findExact(domainId, name, type, value, false));
        if (existing != null) {
            // A prior snapshot may still contain a row another transaction deleted.
            existing = one(mapper.findExact(domainId, name, type, value, true));
        }
        if (existing != null) {
            activate(existing.getId(), record.getTTL());
            return;
        }
        DomainDns created = new DomainDns();
        created.setDomainId(domainId);
        created.setName(name);
        created.setType(type);
        created.setValue(value);
        created.setTtl(record.getTTL());
        created.setStatus(DomainDns.STATUS_ACTIVE);
        created.setDeleted(0);
        created.setCreateBy("task");
        created.setCreateTime(new Date());
        if (record.getType() == Type.A || record.getType() == Type.AAAA) {
            created.setAsnJson(Objects.requireNonNullElse(geo.getAsnJson(value), "{}"));
            created.setCityJson(Objects.requireNonNullElse(geo.getCityJson(value), "{}"));
        }
        try {
            mapper.insert(created);
        } catch (DuplicateKeyException conflict) {
            // Do not assume every unique-key violation belongs to this DNS identity.
            // Full-byte verification also makes a hypothetical hash collision fail closed.
            DomainDns winner = one(mapper.findExact(domainId, name, type, value, true));
            if (winner == null) {
                throw conflict;
            }
            activate(winner.getId(), record.getTTL());
        }
    }

    private DomainDns one(List<DomainDns> matches) {
        if (matches.size() > 1) {
            throw new IllegalStateException("Duplicate live DNS identity; run DNS deduplication before installing the unique index");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private void activate(String id, long ttl) {
        DomainDns patch = new DomainDns();
        patch.setId(id);
        patch.setStatus(DomainDns.STATUS_ACTIVE);
        patch.setTtl(ttl);
        patch.setUpdateBy("task");
        patch.setUpdateTime(new Date());
        mapper.updateById(patch);
    }
}