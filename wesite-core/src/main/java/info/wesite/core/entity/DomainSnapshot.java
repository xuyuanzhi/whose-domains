package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 域名WHOIS快照 - 记录域名每次查询时的WHOIS信息，用于历史对比
 */
@TableName("WEB_DOMAIN_SNAPSHOT")
@Data
public class DomainSnapshot extends BaseEntity {

    /**
     * 域名
     */
    private String domainName;

    /**
     * 关联的域名ID
     */
    private String domainId;

    /**
     * 注册商
     */
    private String registrar;

    /**
     * 注册商URL
     */
    private String registrarUrl;

    /**
     * 注册日期
     */
    private String registCreateDateText;

    /**
     * 更新日期
     */
    private String registUpdateDateText;

    /**
     * 过期日期
     */
    private String registExpiryDateText;

    /**
     * 域名状态
     */
    private String domainStatus;

    /**
     * 名称服务器
     */
    private String nameServers;

    /**
     * DNSSEC
     */
    private String dnssec;

    /**
     * 注册人组织
     */
    private String registrantOrg;

    /**
     * 注册人姓名
     */
    private String registrantName;

    /**
     * 注册人国家
     */
    private String registrantCountry;

    /**
     * 注册人省份
     */
    private String registrantState;

    /**
     * 注册人城市
     */
    private String registrantCity;

    /**
     * 注册人邮箱
     */
    private String registrantEmail;

    /**
     * 技术联系人姓名
     */
    private String techName;

    /**
     * 技术联系人邮箱
     */
    private String techEmail;

    /**
     * WHOIS原始文本
     */
    private String whoisText;

    /**
     * RDAP原始文本
     */
    private String rdapText;

    /**
     * 快照时间
     */
    private Date snapshotTime;

    /**
     * 来源IP
     */
    private String sourceIp;

    /**
     * 从Domain实体创建快照
     */
    public static DomainSnapshot fromDomain(Domain domain) {
        DomainSnapshot snapshot = new DomainSnapshot();
        snapshot.setDomainName(domain.getName());
        snapshot.setDomainId(domain.getId());
        snapshot.setRegistrar(domain.getRegistrar());
        snapshot.setRegistrarUrl(domain.getRegistrarUrl());
        snapshot.setRegistCreateDateText(domain.getRegistCreateDateText());
        snapshot.setRegistUpdateDateText(domain.getRegistUpdateDateText());
        snapshot.setRegistExpiryDateText(domain.getRegistExpiryDateText());
        snapshot.setDomainStatus(domain.getDomainStatus());
        snapshot.setNameServers(domain.getNameServers());
        snapshot.setDnssec(domain.getDnssec());
        snapshot.setRegistrantOrg(domain.getRegistrantOrg());
        snapshot.setRegistrantName(domain.getRegistrantName());
        snapshot.setRegistrantCountry(domain.getRegistrantCountry());
        snapshot.setRegistrantState(domain.getRegistrantState());
        snapshot.setRegistrantCity(domain.getRegistrantCity());
        snapshot.setRegistrantEmail(domain.getRegistrantEmail());
        snapshot.setTechName(domain.getTechName());
        snapshot.setTechEmail(domain.getTechEmail());
        snapshot.setWhoisText(domain.getFinalWhoisText());
        snapshot.setRdapText(domain.getRdapText() != null ? domain.getRdapText() : domain.getParentRdapText());
        snapshot.setSnapshotTime(new Date());
        return snapshot;
    }
}
