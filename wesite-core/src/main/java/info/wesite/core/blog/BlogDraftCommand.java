package info.wesite.core.blog;

public record BlogDraftCommand(
        String slug,
        String title,
        String summary,
        String content,
        String category,
        String tags,
        String metaTitle,
        String metaDescription) {
}
