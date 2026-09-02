package info.wesite.admin.blog;

import java.util.Date;

public final class BlogAdminModels {

    private BlogAdminModels() {
    }

    public record ListRequest(Integer page, Integer limit, String keyword, Integer status) {
    }

    public record IdRequest(String id) {
    }

    public record SaveRequest(
            String id,
            String slug,
            String title,
            String summary,
            String content,
            String author,
            String category,
            String tags,
            String metaTitle,
            String metaDescription) {
    }

    public record PreviewRequest(String content) {
    }

    public record PreviewResponse(String html) {
    }

    public record ListItemResponse(
            String id,
            String slug,
            String title,
            String summary,
            String author,
            String category,
            String tags,
            Integer status,
            Date publishDate,
            Date contentUpdatedAt) {
    }

    public record DetailResponse(
            String id,
            String slug,
            String title,
            String summary,
            String content,
            String author,
            String category,
            String tags,
            String metaTitle,
            String metaDescription,
            Integer status,
            Date publishDate,
            Date contentUpdatedAt) {
    }
}
