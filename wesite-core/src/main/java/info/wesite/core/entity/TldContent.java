package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * TLD 内容详情（用于 SEO 深化 TLD 页）
 */
@TableName("WEB_TLD_CONTENT")
@Data
@EqualsAndHashCode(callSuper = true)
public class TldContent extends BaseEntity {

    /** TLD 不含点，如 com / io / ai */
    private String tld;

    /** 简介 HTML */
    private String introHtml;

    /** 历史背景 HTML */
    private String historyHtml;

    /** 典型用途 HTML */
    private String useCasesHtml;

    /** 知名网站 JSON 数组，如 [{"name":"Google","url":"google.com"},...] */
    private String famousSitesJson;

    /** 注册要求 HTML */
    private String registerRequirementsHtml;
}
