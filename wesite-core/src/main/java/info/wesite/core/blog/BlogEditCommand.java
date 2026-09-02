package info.wesite.core.blog;

public record BlogEditCommand(
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
