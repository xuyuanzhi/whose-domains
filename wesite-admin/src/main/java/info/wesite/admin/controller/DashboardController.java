package info.wesite.admin.controller;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.time.DateFormatUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.admin.view.DashboardSummaryView;
import info.wesite.admin.view.DashboardSummaryView.RecentContactView;
import info.wesite.admin.view.DashboardSummaryView.RecentPostView;
import info.wesite.core.config.AccessControl;
import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.entity.ContactInfo;
import info.wesite.core.entity.User;
import info.wesite.core.service.BlogPostService;
import info.wesite.core.service.ContactInfoService;
import info.wesite.core.service.DomainTldExtService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.service.UserService;
import info.wesite.core.view.ResponseJson;

@RestController
@RequestMapping("/dashboard")
@AccessControl(level = AccessControl.Level.SESSION)
public class DashboardController {

    private static final int RECENT_LIMIT = 5;
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm";

    private final UserService userService;
    private final DomainTldService domainTldService;
    private final DomainTldExtService domainTldExtService;
    private final BlogPostService blogPostService;
    private final ContactInfoService contactInfoService;

    public DashboardController(
            UserService userService,
            DomainTldService domainTldService,
            DomainTldExtService domainTldExtService,
            BlogPostService blogPostService,
            ContactInfoService contactInfoService) {
        this.userService = userService;
        this.domainTldService = domainTldService;
        this.domainTldExtService = domainTldExtService;
        this.blogPostService = blogPostService;
        this.contactInfoService = contactInfoService;
    }

    @GetMapping("/summary")
    public ResponseJson<DashboardSummaryView> summary() {
        long userCount = userService.count(
            Wrappers.<User>lambdaQuery().eq(User::getUserType, User.TYPE_PERSON));
        long tldCount = domainTldService.count();
        long sldCount = domainTldExtService.count();
        long draftPostCount = countPosts(BlogPost.POST_STATUS_DRAFT);
        long publishedPostCount = countPosts(BlogPost.POST_STATUS_PUBLISHED);
        long pendingContactCount = contactInfoService.count(
            Wrappers.<ContactInfo>lambdaQuery()
                .eq(ContactInfo::getStatus, BaseEntity.STATUS_ACTIVE));

        DashboardSummaryView summary = new DashboardSummaryView(
            userCount,
            tldCount,
            sldCount,
            draftPostCount,
            publishedPostCount,
            pendingContactCount,
            loadRecentPosts(),
            loadRecentContacts());
        return ResponseJson.success(summary);
    }

    private long countPosts(int status) {
        return blogPostService.count(
            Wrappers.<BlogPost>lambdaQuery().eq(BlogPost::getStatus, status));
    }

    private List<RecentPostView> loadRecentPosts() {
        Page<BlogPost> page = blogPostService.page(
            Page.of(1, RECENT_LIMIT, false),
            Wrappers.<BlogPost>lambdaQuery()
                .select(
                    BlogPost::getId,
                    BlogPost::getTitle,
                    BlogPost::getSlug,
                    BlogPost::getStatus,
                    BlogPost::getContentUpdatedAt,
                    BlogPost::getCreateTime)
                .orderByDesc(BlogPost::getContentUpdatedAt, BlogPost::getCreateTime));

        return page.getRecords().stream()
            .limit(RECENT_LIMIT)
            .map(post -> new RecentPostView(
                post.getId(),
                post.getTitle(),
                post.getSlug(),
                post.getStatus(),
                format(firstNonNull(post.getContentUpdatedAt(), post.getCreateTime()))))
            .toList();
    }

    private List<RecentContactView> loadRecentContacts() {
        Page<ContactInfo> page = contactInfoService.page(
            Page.of(1, RECENT_LIMIT, false),
            Wrappers.<ContactInfo>lambdaQuery()
                .select(
                    ContactInfo::getId,
                    ContactInfo::getName,
                    ContactInfo::getSubject,
                    ContactInfo::getStatus,
                    ContactInfo::getCreateTime)
                .orderByDesc(ContactInfo::getCreateTime));

        return page.getRecords().stream()
            .limit(RECENT_LIMIT)
            .map(contact -> new RecentContactView(
                contact.getId(),
                contact.getName(),
                contact.getSubject(),
                contact.getStatus(),
                format(contact.getCreateTime())))
            .toList();
    }

    private static Date firstNonNull(Date first, Date second) {
        return first == null ? second : first;
    }

    private static String format(Date value) {
        return value == null ? "" : DateFormatUtils.format(value, DATE_TIME_PATTERN);
    }
}
