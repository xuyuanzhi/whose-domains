package info.wesite.core.entity;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName("WEB_DOMAIN")
@Data
public class Domain extends BaseEntity {

	private static final Logger log = LoggerFactory.getLogger(Domain.class);

	public static final int STATUS_NEW = 0;

	// 主域名
//	public static final int LEVEL_1 = 1;
//	//子域名
//	public static final int LEVEL_2 = 2;

	// 主域名
	private String name;
//	private String ips;

	// 顶级域名
	private String tldName;

	// 二级域名
	private String sldName;

//	private String homePageUrl;
//	private String homePageHtml;
//	private String homePageTitle;
//	private String homePageMetaDesc;
//	
//	private String serverName;
//	private String serverLocation;
	private String icpNo;
	private String icpName;

	@TableField("REGISTRY_DOMAIN_ID")
	private String registryDomainID;

	private String registrar;

	@TableField("REGISTRAR_IANA_ID")
	private String registrarIanaID;

	private String registrarUrl;
//    private String expirationDate;

	private String domainStatus;

	private String nameServers;

	private String registCreateDateText;
	private String registUpdateDateText;
	private String registExpiryDateText;

	private String registrantOrg;
	private String registrantName;
	private String registrantCity;
	private String registrantState;
	private String registrantCountry;
	private String registrantPhone;
	private String registrantEmail;

	private String techName;
	private String techPhone;
	private String techEmail;

	private String dnssec;

	// 失败状态下可重试更新的天数
	private Integer retryDays;

	private String parentWhoisServer;
	private String parentWhoisText;

	private String whoisServer;
	private String whoisText;

	private String parentRdapServer;
	private String parentRdapText;

	private String rdapServer;
	private String rdapText;

	private Date refreshDnsTime;
	private Date refreshWebTime;

//	private Integer level;

	private String sourceIp;

	// 请求刷新时间
	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	private LocalDateTime requestRefreshTime;
	// 请求刷新的IP
	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	private String requestRefreshIp;

	// 请求刷新按钮是否显示
	public boolean isRequestRefreshBtnEnable() {
		return requestRefreshTime == null && DateUtils.addDays(getLastOperateTime(), 30).before(new Date());
	}

	public String getFinalWhoisServer() {
		if (StringUtils.isNotBlank(whoisServer)) {
			return whoisServer;
		} else if (StringUtils.isNotBlank(parentWhoisServer)) {
			return parentWhoisServer;
		} else {
			return null;
		}
	}

	public String getFinalWhoisText() {
		if (StringUtils.isNotBlank(whoisText)) {
			return whoisText;
		} else if (StringUtils.isNotBlank(parentWhoisText)) {
			return parentWhoisText;
		} else {
			return null;
		}
	}

	public String getRdapUrl() {
		if (rdapServer == null) {
			return null;
		}

		return rdapServer + "domain/" + name;
	}

//	public String getLocation() {
//		if (StringUtils.isBlank(serverLocation)) {
//			return null;
//		}
//
//		try {
//			JSONObject json = JSON.parseObject(serverLocation);
//
//			String country = null;
//			String city = null;
//
//			if (json.containsKey("country")) {
//				country = json.getJSONObject("country").getString("name");
//			}
//
//			if (json.containsKey("city")) {
//				city = json.getJSONObject("city").getString("name");
//			}
//
//			if (StringUtils.isNotBlank(country) && StringUtils.isNotBlank(city)) {
//				return city + ", " + country;
//			} else if (StringUtils.isNotBlank(country)) {
//				return country;
//			} else if (StringUtils.isNotBlank(city)) {
//				return city;
//			} else {
//				return json.getString("name");
//			}
//		} catch (JSONException e) {
//			return serverLocation;
//		}
//	}

	public List<String> getNameServerList() {
		if (nameServers == null) {
			return Collections.emptyList();
		} else {
			return Arrays.asList(nameServers.split(","));
		}
	}

	public String getPrettyRdapText() {
		if (StringUtils.isNotBlank(rdapText)) {
			return JSON.toJSONString(JSON.parseObject(rdapText), JSONWriter.Feature.PrettyFormat);
		} else if (StringUtils.isNotBlank(parentRdapText)) {
			return JSON.toJSONString(JSON.parseObject(parentRdapText), JSONWriter.Feature.PrettyFormat);
		} else {
			return null;
		}
	}

	public Date getRegistCreateDate() {
		if (StringUtils.isBlank(registCreateDateText)) {
			return null;
		}
		try {
			if (registCreateDateText.length() < 8) {
				return null;
			} else if (registCreateDateText.length() < 10) {
				String dateStr = registCreateDateText.substring(0, 8);
				return DateUtils.parseDate(dateStr, "yyyyMMdd");
			} else {
				String dateStr = registCreateDateText.substring(0, 10);
				return DateUtils.parseDate(dateStr, "yyyy-MM-dd");
			}
		} catch (Exception e) {
			log.warn("Failed to parse create date: {}", registCreateDateText, e);
			return null;
		}
	}

	public Date getExpiryDate() {
		if (StringUtils.isBlank(registExpiryDateText)) {
			return null;
		}

		if (name.endsWith(".edu")) {
			try {
				return DateUtils.parseDate(registExpiryDateText, Locale.ENGLISH, "dd-MMM-yyyy");
			} catch (ParseException e) {
				log.warn("Failed to parse edu expiry date: {}", registExpiryDateText, e);
				return null;
			}
		} else {
			try {
				if (registExpiryDateText.length() < 8) {
					return null;
				} else if (registExpiryDateText.length() < 10) {
					String dateStr = registExpiryDateText.substring(0, 8);
					return DateUtils.parseDate(dateStr, "yyyyMMdd");
				} else {
					String dateStr = registExpiryDateText.substring(0, 10);
					return DateUtils.parseDate(dateStr, "yyyy-MM-dd");
				}
			} catch (Exception e) {
				log.warn("Failed to parse expiry date: {}", registExpiryDateText, e);
				return null;
			}
		}
	}

	/**
	 * 获取最后操作时间
	 * 
	 * @return
	 */
	private Date getLastOperateTime() {
		if (this.getUpdateTime() != null) {
			return this.getUpdateTime();
		} else {
			return this.getCreateTime();
		}
	}

	/**
	 * 判断域名是否可以更新
	 * 
	 * @return
	 */
	public boolean isUpdatable() {
		if (this.getStatus() == Domain.STATUS_INACTIVE) {// 失效域名至少间隔12小时才能更新一次
			return DateUtils.addHours(new Date(), -12).after(this.getLastOperateTime());
		} else {
			if (this.getRdapServer() == null && this.getWhoisServer() == null && this.getParentRdapServer() == null
					&& this.getParentWhoisServer() == null && this.getRefreshDnsTime() == null) {
				return true;
			}

			if (this.getExpiryDate() == null) {
				return DateUtils.addDays(new Date(), -7).after(this.getLastOperateTime());
			} else {
				if (this.getExpiryDate().before(DateUtils.addDays(new Date(), 30))) {
					return DateUtils.addDays(new Date(), -1).after(this.getLastOperateTime());
				} else {
					return DateUtils.addDays(new Date(), -90).after(this.getLastOperateTime());
				}
			}
		}
	}

	public String getRegistrantLocation() {
		StringBuffer ss = new StringBuffer();
		if (registrantCity != null) {
			ss.append(registrantCity);
		}

		if (registrantState != null) {
			if (ss.length() > 0) {
				ss.append(", ").append(registrantState);
			} else {
				ss.append(registrantState);
			}
		}

		if (registrantCountry != null) {
			if (ss.length() > 0) {
				ss.append(", ").append(registrantCountry);
			} else {
				ss.append(registrantCountry);
			}
		}

		return ss.toString();
	}

	public String getMetaDescription() {
		return "Get a complete domain analysis of domain " + name
				+ ". Check DNS records, sub domains, server ips, server location, WHOIS data, and RDAP details. Instantly access registration info, name servers, and ownership information.";
	}
}
