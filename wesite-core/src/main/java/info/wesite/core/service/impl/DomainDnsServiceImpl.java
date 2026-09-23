package info.wesite.core.service.impl;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Type;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainDns;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.mapper.DomainDnsMapper;
import info.wesite.core.mapper.DomainMapper;
import info.wesite.core.mapper.DomainSiteMapper;
import info.wesite.core.service.DomainDnsService;

@Service
public class DomainDnsServiceImpl extends ServiceImpl<DomainDnsMapper, DomainDns> implements DomainDnsService {

	@Autowired
	private DomainMapper domainMapper;
	@Autowired
	private DomainSiteMapper domainSiteMapper;
	@Autowired
	private DomainDnsRecordWriter recordWriter;

	@Transactional
	@Override
	public boolean refreshByDomain(Domain domain) throws Exception {
		LambdaQueryWrapper<DomainDns> query = Wrappers.<DomainDns>lambdaQuery()
				.eq(DomainDns::getDomainId, domain.getId()).eq(DomainDns::getName, domain.getName() + ".");
		Long total = getBaseMapper().selectCount(query);
		if (total > 0) {
			DomainDns dd = new DomainDns();
			dd.setStatus(DomainDns.STATUS_INACTIVE);
			getBaseMapper().update(dd, query);
		}

		int[] types = { Type.A, Type.AAAA, Type.CNAME, Type.TXT, Type.MX };

		for (int type : types) {
			Lookup lookup = new Lookup(domain.getName(), type);
			org.xbill.DNS.Record[] rs = lookup.run();
			if (rs != null) {
				for (org.xbill.DNS.Record r : rs) {
					recordWriter.saveObserved(domain.getId(), r);
				}
			}
		}

		domain.setRefreshDnsTime(new Date());
		domainMapper.updateById(domain);
		return true;
	}

	@Override
	public boolean refreshByDomainSite(DomainSite site) throws Exception {
		LambdaQueryWrapper<DomainDns> query = Wrappers.<DomainDns>lambdaQuery()
				.eq(DomainDns::getDomainId, site.getDomainId()).eq(DomainDns::getName, site.getName() + ".");
		Long total = getBaseMapper().selectCount(query);
		if (total > 0) {
			DomainDns dd = new DomainDns();
			dd.setStatus(DomainDns.STATUS_INACTIVE);
			getBaseMapper().update(dd, query);
		}

		int[] types = { Type.A, Type.AAAA, Type.CNAME, Type.TXT, Type.MX };

		for (int type : types) {
			Lookup lookup = new Lookup(site.getName(), type);
			org.xbill.DNS.Record[] rs = lookup.run();
			if (rs != null) {
				for (org.xbill.DNS.Record r : rs) {
					recordWriter.saveObserved(site.getDomainId(), r);
				}
			}
		}

		site.setRefreshDnsTime(LocalDateTime.now());
		domainSiteMapper.updateById(site);
		return true;
	}

	@Override
	public long countDomainIdsByIp(String ip) {
		return baseMapper.countDistinctDomainIdsByIp(ip, BaseEntity.STATUS_ACTIVE);
	}

	@Override
	public List<String> listDomainIdsByIp(String ip, int page, int pageSize) {
		long offset = (long)(page - 1) * pageSize;
		return baseMapper.selectDistinctDomainIdsByIp(ip, BaseEntity.STATUS_ACTIVE, offset, pageSize);
	}
}
