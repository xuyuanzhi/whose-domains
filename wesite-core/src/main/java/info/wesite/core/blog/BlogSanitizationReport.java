package info.wesite.core.blog;

import java.util.List;

public record BlogSanitizationReport(long scanned, List<ChangedPost> changedPosts) {

    public BlogSanitizationReport {
        changedPosts = List.copyOf(changedPosts);
    }

    public long changed() {
        return changedPosts.size();
    }

    public record ChangedPost(String id, String slug) {
    }
}
