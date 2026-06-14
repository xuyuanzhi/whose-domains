package info.wesite.core.service;

import com.baomidou.mybatisplus.extension.service.IService;

import info.wesite.core.entity.DomainSite;

public interface DomainSiteService extends IService<DomainSite> {

	public boolean refreshWebByName(String name);
	
	public boolean refreshWeb(DomainSite site);
}
