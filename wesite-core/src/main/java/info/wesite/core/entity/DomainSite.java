package info.wesite.core.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName("WEB_DOMAIN_SITE")
@Data
public class DomainSite extends BaseEntity {

	private String domainId;
	private String mainName;
	private String name;

	private String homePageUrl;
	private String homePageHtml;
	private String homePageTitle;
	private String homePageMetaDesc;

	private String serverName;
	private String serverLocation;

	private String sourceIp;

	private LocalDateTime refreshDnsTime;
	private LocalDateTime refreshWebTime;

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

	public boolean isUpdatable() {
		if (this.getStatus() == Domain.STATUS_INACTIVE) {// 失效域名最多2小时更新一次
			return DateUtils.addHours(new Date(), -2).after(this.getLastOperateTime());
		} else {
			if (StringUtils.isBlank(this.getHomePageUrl())) {
				return DateUtils.addDays(new Date(), -1).after(this.getLastOperateTime());
			} else {
				return DateUtils.addDays(new Date(), -5).after(this.getLastOperateTime());
			}
		}
	}

	public String getMetaDescription() {
		return "Analyze subdomain " + name
				+ ": trace its DNS server (nameserver) records, A/AAAA/CNAME/TXT data, IP addresses, and IP locations. See its relation to the main domain "
				+ mainName + ".";
	}
}
