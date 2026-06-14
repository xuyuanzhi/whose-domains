package info.wesite.core.entity;

import org.apache.commons.lang3.StringUtils;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName("WEB_DOMAIN_TLD")
@Data
public class DomainTld extends BaseEntity {

	private String name;

	private String dotName;

	private String displayName;

	private String type;

	private String orgName;

	private String orgAddr;

	private String orgAddr2;

	private String orgState;

	private String orgCity;

	private String orgZip;

	private String orgCountry;

	private String adminName;

	private String adminOrg;

	private String adminEmail;

	private String adminPhone;

	private String adminFax;

	private String adminAddr;

	private String adminAddr2;

	private String adminState;

	private String adminCity;

	private String adminZip;

	private String adminCountry;

	private String techName;

	private String techOrg;

	private String techEmail;

	private String techPhone;

	private String techFax;

	private String techAddr;

	private String techAddr2;

	private String techState;

	private String techCity;

	private String techZip;

	private String techCountry;

	private String registrationUrl;

	private String whoisServer;

	private String rdapServer;

	private String lastUpdatedDate;

	private String registrationDate;

	private String sourceUrl;

	private String sourceHtml;

	public String getFullOrgAddr() {
		if (StringUtils.isNotBlank(orgAddr) && StringUtils.isNotBlank(orgAddr2)) {
			return orgAddr + ", " + orgAddr2;
		} else if (StringUtils.isNotBlank(orgAddr)) {
			return orgAddr;
		} else if (StringUtils.isNotBlank(orgAddr2)) {
			return orgAddr2;
		} else {
			return null;
		}
	}

	public String getFullAdminAddr() {
		if (StringUtils.isNotBlank(adminAddr) && StringUtils.isNotBlank(adminAddr2)) {
			return adminAddr + ", " + adminAddr2;
		} else if (StringUtils.isNotBlank(adminAddr)) {
			return adminAddr;
		} else if (StringUtils.isNotBlank(adminAddr2)) {
			return adminAddr2;
		} else {
			return null;
		}
	}

	public String getFullTechAddr() {
		if (StringUtils.isNotBlank(techAddr) && StringUtils.isNotBlank(techAddr2)) {
			return techAddr + ", " + techAddr2;
		} else if (StringUtils.isNotBlank(techAddr)) {
			return techAddr;
		} else if (StringUtils.isNotBlank(techAddr2)) {
			return techAddr2;
		} else {
			return null;
		}
	}

	public String getMetaDescription() {
		return "Get the authoritative profile for " + displayName
				+ ". Access registry organization details, official WHOIS/RDAP server addresses, and explore a list of registered domains under this extension.";
	}
}
