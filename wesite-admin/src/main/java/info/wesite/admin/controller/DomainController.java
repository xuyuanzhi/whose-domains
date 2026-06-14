package info.wesite.admin.controller;

import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.admin.view.SearchParam;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.entity.DomainTldExt;
import info.wesite.core.service.DomainTldExtService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "域名管理")
@RestController
@RequestMapping("/domain")
public class DomainController {

	@Autowired
	private DomainTldService domainTldService;
	@Autowired
	private DomainTldExtService domainTldExtService;

	@PostMapping("/tld/list")
	public ResponseJson<DomainTld> tldList(@RequestBody SearchParam param) {
		if (param.getPage() == null) {
			param.setPage(1);
		}

		if (param.getLimit() == null) {
			param.setLimit(20);
		}

		LambdaQueryWrapper<DomainTld> query = Wrappers.<DomainTld>lambdaQuery().orderByAsc(DomainTld::getName);
		if (StringUtils.isNotBlank(param.getKeyword())) {
			query.and(q -> q.like(DomainTld::getName, param.getKeyword()).or().like(DomainTld::getDisplayName,
					param.getKeyword()));
		}

		Page<DomainTld> page = domainTldService.page(Page.of(param.getPage(), param.getLimit()), query);

		return ResponseJson.success(page.getRecords(), page.getTotal());
	}

	@PostMapping("/tld/detail")
	public ResponseJson<DomainTld> tldDetail(@RequestBody DomainTld param) {
		if (StringUtils.isBlank(param.getId())) {
			return ResponseJson.failure("id is required.");
		}

		DomainTld byId = domainTldService.getById(param.getId());
		if (byId == null) {
			return ResponseJson.failure("id is invalid.");
		}

		return ResponseJson.success(byId);
	}

	@PostMapping("/tld/save")
	public ResponseJson<DomainTld> tldSave(@RequestBody DomainTld param) {
		if (StringUtils.isBlank(param.getId())) {
			return ResponseJson.failure("id is required.");
		}

		DomainTld byId = domainTldService.getById(param.getId());
		if (byId == null) {
			return ResponseJson.failure("id is invalid.");
		}

		byId.setOrgName(param.getOrgName());
		byId.setOrgAddr(param.getOrgAddr());
		byId.setOrgAddr2(param.getOrgAddr2());
		byId.setOrgCountry(param.getOrgCountry());
		byId.setAdminName(param.getAdminName());
		byId.setAdminOrg(param.getAdminOrg());
		byId.setAdminAddr(param.getAdminAddr());
		byId.setAdminAddr2(param.getAdminAddr2());
		byId.setAdminCountry(param.getAdminCountry());
		byId.setAdminEmail(param.getAdminEmail());
		byId.setAdminPhone(param.getAdminPhone());
		byId.setAdminFax(param.getAdminFax());
		byId.setTechName(param.getTechName());
		byId.setTechOrg(param.getTechOrg());
		byId.setTechAddr(param.getTechAddr());
		byId.setTechAddr2(param.getTechAddr2());
		byId.setTechCountry(param.getTechCountry());
		byId.setTechEmail(param.getTechEmail());
		byId.setTechPhone(param.getTechPhone());
		byId.setTechFax(param.getTechFax());
		byId.setUpdateBy("admin");
		byId.setUpdateTime(new Date());

		if (domainTldService.updateById(byId)) {
			return ResponseJson.success();
		} else {
			return ResponseJson.failure("Fail to save.");
		}
	}

	@PostMapping("/sld/list")
	public ResponseJson<DomainTldExt> sldList(@RequestBody SearchParam param) {
		if (param.getPage() == null) {
			param.setPage(1);
		}

		if (param.getLimit() == null) {
			param.setLimit(20);
		}

		LambdaQueryWrapper<DomainTldExt> query = Wrappers.<DomainTldExt>lambdaQuery()
				.orderByAsc(DomainTldExt::getTldName, DomainTldExt::getName);
		if (StringUtils.isNotBlank(param.getKeyword())) {
			query.like(DomainTldExt::getName, param.getKeyword());
		}

		Page<DomainTldExt> page = domainTldExtService.page(Page.of(param.getPage(), param.getLimit()), query);

		return ResponseJson.success(page.getRecords(), page.getTotal());
	}

	@PostMapping("/sld/detail")
	public ResponseJson<DomainTldExt> sldDetail(@RequestBody DomainTldExt param) {
		if (StringUtils.isBlank(param.getId())) {
			return ResponseJson.failure("id is required.");
		}

		DomainTldExt byId = domainTldExtService.getById(param.getId());
		if (byId == null) {
			return ResponseJson.failure("id is invalid.");
		}

		return ResponseJson.success(byId);
	}

	@PostMapping("/sld/save")
	public ResponseJson<DomainTldExt> sldSave(@RequestBody DomainTldExt param) {
		if (StringUtils.isBlank(param.getName())) {
			return ResponseJson.failure("Name is required.");
		}

		String name = param.getName().toLowerCase();

		DomainTldExt byName = domainTldExtService
				.getOne(Wrappers.<DomainTldExt>lambdaQuery().eq(DomainTldExt::getName, name));
		if (byName != null && (StringUtils.isBlank(param.getId()) || !param.getId().equals(byName.getId()))) {
			return ResponseJson.failure("Name " + param.getName() + " already exists.");
		}

		String tldName = DomainUtils.getTldName(param.getName());
		DomainTld tld = domainTldService.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getDotName, tldName));
		if (tld == null) {
			return ResponseJson.failure("TLD " + tldName + " doesn't exist.");
		}

		DomainTldExt ext = null;
		if (StringUtils.isBlank(param.getId())) {
			ext = new DomainTldExt();
			ext.setId(RandomUtils.generateId());
			ext.setStatus(DomainTldExt.STATUS_ACTIVE);
			ext.setCreateBy("admin");
			ext.setCreateTime(new Date());
		} else {
			ext = domainTldExtService.getById(param.getId());
			if (ext == null) {
				return ResponseJson.failure("id is invalid.");
			}

			ext.setStatus(param.getStatus());
			ext.setUpdateBy("admin");
			ext.setUpdateTime(new Date());
		}

		ext.setName(name);
		ext.setDotName("." + name);
		ext.setTldName(tldName);
		ext.setCountryName(param.getCountryName());
		ext.setNote(param.getNote());

		if (domainTldExtService.saveOrUpdate(ext)) {
			return ResponseJson.success();
		} else {
			return ResponseJson.failure("Fail to save.");
		}
	}

}
