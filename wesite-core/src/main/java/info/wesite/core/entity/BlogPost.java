package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 博客文章实体
 */
@TableName("WEB_BLOG_POST")
@Data
@EqualsAndHashCode(callSuper = true)
public class BlogPost extends BaseEntity {

    public static final int POST_STATUS_DRAFT     = 0;
    public static final int POST_STATUS_PUBLISHED = 1;

    /** URL 友好标识，如 how-to-check-domain-expiry */
    private String slug;

    /** 文章标题 */
    private String title;

    /** 摘要（用于列表页 & meta description） */
    private String summary;

    /** 正文 HTML */
    private String content;

    /** 封面图片 URL */
    private String cover;

    /** 作者名 */
    private String author;

    /** 标签，逗号分隔，如 "whois,domain,lookup" */
    private String tags;

    /** 分类 slug，如 "domain-tools" */
    private String category;

    /** 发布日期 */
    private Date publishDate;

    /** 浏览次数 */
    private Integer viewCount;

    /** meta title（为空则用 title） */
    private String metaTitle;

    /** meta description（为空则用 summary） */
    private String metaDescription;

    // ---- 非持久化字段 ----

    @TableField(exist = false)
    private String publishDateText;

    @TableField(exist = false)
    private String[] tagArray;

    public String getEffectiveMetaTitle() {
        return metaTitle != null && !metaTitle.isEmpty() ? metaTitle : title;
    }

    public String getEffectiveMetaDescription() {
        return metaDescription != null && !metaDescription.isEmpty() ? metaDescription : summary;
    }
}
