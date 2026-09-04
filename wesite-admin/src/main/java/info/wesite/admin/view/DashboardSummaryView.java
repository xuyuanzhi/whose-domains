package info.wesite.admin.view;

import java.util.List;

/**
 * 管理后台工作台汇总数据。
 *
 * <p>该视图只包含工作台展示所需字段，避免直接序列化业务实体。</p>
 */
public record DashboardSummaryView(
        long userCount,
        long tldCount,
        long sldCount,
        long draftPostCount,
        long publishedPostCount,
        long pendingContactCount,
        List<RecentPostView> recentPosts,
        List<RecentContactView> recentContacts) {

    public DashboardSummaryView {
        recentPosts = recentPosts == null ? List.of() : List.copyOf(recentPosts);
        recentContacts = recentContacts == null ? List.of() : List.copyOf(recentContacts);
    }

    public record RecentPostView(
            String id,
            String title,
            String slug,
            Integer status,
            String updatedAtText) {
    }

    public record RecentContactView(
            String id,
            String name,
            String subject,
            Integer status,
            String createTimeText) {
    }
}
