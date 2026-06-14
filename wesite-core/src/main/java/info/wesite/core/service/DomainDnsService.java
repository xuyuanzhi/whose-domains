package info.wesite.core.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainDns;
import info.wesite.core.entity.DomainSite;

public interface DomainDnsService extends IService<DomainDns> {

	public boolean refreshByDomain(Domain domain) throws Exception;

	public boolean refreshByDomainSite(DomainSite site) throws Exception;

	/**
	 * 统计托管在指定IP上的域名数量（去重）
	 */
	long countDomainIdsByIp(String ip);

	/**
	 * 分页获取托管在指定IP上的域名ID列表（去重）
	 */
	List<String> listDomainIdsByIp(String ip, int page, int pageSize);
}

