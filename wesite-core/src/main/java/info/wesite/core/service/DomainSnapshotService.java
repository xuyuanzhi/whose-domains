package info.wesite.core.service;

import com.baomidou.mybatisplus.extension.service.IService;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSnapshot;

public interface DomainSnapshotService extends IService<DomainSnapshot> {

    /**
     * 为域名保存快照（如果与最近一次快照无变化则跳过）
     * @param domain 域名实体
     * @param sourceIp 来源IP
     * @return 保存的快照，如无变化返回null
     */
    DomainSnapshot saveSnapshotIfChanged(Domain domain, String sourceIp);
}
