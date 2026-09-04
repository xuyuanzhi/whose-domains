package info.wesite.admin.controller;

import java.util.Date;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
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

	private final DomainTldService domainTldService;
	private final DomainTldExtService domainTldExtService;

	public DomainController(DomainTldService domainTldService, DomainTldExtService domainTldExtService) {
		this.domainTldService = domainTldService;
		this.domainTldExtService = domainTldExtService;
	}

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
		String id = param == null ? null : StringUtils.trimToNull(param.getId());
		if (id == null) {
			return ResponseJson.failure("顶级域名ID不能为空");
		}

		DomainTld byId = domainTldService.getById(id);
		if (byId == null) {
			return ResponseJson.failure("顶级域名不存在");
		}

		return ResponseJson.success(byId);
	}

	@PostMapping("/tld/save")
	public ResponseJson<DomainTld> tldSave(@RequestBody DomainTld param) {
		String id = param == null ? null : StringUtils.trimToNull(param.getId());
		if (id == null) {
			return ResponseJson.failure("顶级域名ID不能为空");
		}

		DomainTld byId = domainTldService.getById(id);
		if (byId == null) {
			return ResponseJson.failure("顶级域名不存在");
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
			return ResponseJson.failure("保存失败");
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
		String id = param == null ? null : StringUtils.trimToNull(param.getId());
		if (id == null) {
			return ResponseJson.failure("二级保留域名ID不能为空");
		}

		DomainTldExt byId = domainTldExtService.getById(id);
		if (byId == null) {
			return ResponseJson.failure("二级保留域名不存在");
		}

		return ResponseJson.success(byId);
	}

	@PostMapping("/sld/save")
	public ResponseJson<DomainTldExt> sldSave(@RequestBody DomainTldExt param) {
		String name = param == null ? null : StringUtils.trimToNull(param.getName());
		if (name == null) {
			return ResponseJson.failure("二级保留域名名称不能为空");
		}
		name = name.toLowerCase(Locale.ROOT);
		String id = StringUtils.trimToNull(param.getId());

		if (id != null && !isEditableStatus(param.getStatus())) {
			return ResponseJson.failure("二级保留域名状态只能是启用或禁用");
		}

		DomainTldExt byName = domainTldExtService
				.getOne(Wrappers.<DomainTldExt>lambdaQuery().eq(DomainTldExt::getName, name));
		if (byName != null && (id == null || !id.equals(byName.getId()))) {
			return ResponseJson.failure("二级保留域名名称已存在");
		}

		String tldName = DomainUtils.getTldName(name);
		DomainTld tld = domainTldService.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getDotName, tldName));
		if (tld == null) {
			return ResponseJson.failure("所属顶级域名不存在");
		}

		DomainTldExt ext = null;
		if (id == null) {
			ext = new DomainTldExt();
			ext.setId(RandomUtils.generateId());
			ext.setStatus(DomainTldExt.STATUS_ACTIVE);
			ext.setCreateBy("admin");
			ext.setCreateTime(new Date());
		} else {
			ext = domainTldExtService.getById(id);
			if (ext == null) {
				return ResponseJson.failure("二级保留域名不存在");
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
			return ResponseJson.failure("保存失败");
		}
	}

	private static boolean isEditableStatus(Integer status) {
		return status != null
				&& (status == DomainTldExt.STATUS_ACTIVE || status == DomainTldExt.STATUS_INACTIVE);
	}

}
