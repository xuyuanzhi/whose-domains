package info.wesite.core.service.impl;

import java.util.Date;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSnapshot;
import info.wesite.core.mapper.DomainSnapshotMapper;
import info.wesite.core.service.DomainSnapshotService;
import info.wesite.core.utils.RandomUtils;

@Service
public class DomainSnapshotServiceImpl extends ServiceImpl<DomainSnapshotMapper, DomainSnapshot>
        implements DomainSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(DomainSnapshotServiceImpl.class);

    @Override
    public DomainSnapshot saveSnapshotIfChanged(Domain domain, String sourceIp) {
        if (domain == null || domain.getName() == null) {
            return null;
        }

        try {
            // 查询此域名最近一次快照
            DomainSnapshot lastSnapshot = getOne(
                    Wrappers.<DomainSnapshot>lambdaQuery()
                            .eq(DomainSnapshot::getDomainName, domain.getName())
                            .eq(DomainSnapshot::getStatus, DomainSnapshot.STATUS_ACTIVE)
                            .orderByDesc(DomainSnapshot::getSnapshotTime)
                            .last("LIMIT 1"));

            // 创建新快照
            DomainSnapshot newSnapshot = DomainSnapshot.fromDomain(domain);
            newSnapshot.setId(RandomUtils.generateId());
            newSnapshot.setSourceIp(sourceIp);
            newSnapshot.setStatus(DomainSnapshot.STATUS_ACTIVE);
            newSnapshot.setCreateBy(sourceIp);
            newSnapshot.setCreateTime(new Date());

            // 如果有上一次快照，比较关键字段是否发生变化
            if (lastSnapshot != null) {
                boolean changed = hasChanged(lastSnapshot, newSnapshot);
                if (!changed) {
                    logger.debug("域名 {} 的WHOIS信息未发生变化，跳过快照保存", domain.getName());
                    return null;
                }
            }

            // 保存新快照
            save(newSnapshot);
            logger.info("域名 {} 的WHOIS快照已保存，快照ID: {}", domain.getName(), newSnapshot.getId());
            return newSnapshot;

        } catch (Exception e) {
            logger.error("保存域名 {} 的快照失败", domain.getName(), e);
            return null;
        }
    }

    /**
     * 比较两个快照的关键字段是否有变化
     */
    private boolean hasChanged(DomainSnapshot old, DomainSnapshot current) {
        return !Objects.equals(old.getRegistrar(), current.getRegistrar())
                || !Objects.equals(old.getRegistExpiryDateText(), current.getRegistExpiryDateText())
                || !Objects.equals(old.getRegistUpdateDateText(), current.getRegistUpdateDateText())
                || !Objects.equals(old.getDomainStatus(), current.getDomainStatus())
                || !Objects.equals(old.getNameServers(), current.getNameServers())
                || !Objects.equals(old.getDnssec(), current.getDnssec())
                || !Objects.equals(old.getRegistrantOrg(), current.getRegistrantOrg())
                || !Objects.equals(old.getRegistrantName(), current.getRegistrantName())
                || !Objects.equals(old.getRegistrantCountry(), current.getRegistrantCountry())
                || !Objects.equals(old.getRegistrantEmail(), current.getRegistrantEmail())
                || !Objects.equals(old.getTechName(), current.getTechName())
                || !Objects.equals(old.getTechEmail(), current.getTechEmail());
    }
}
