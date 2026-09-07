package info.wesite.core.blog;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import info.wesite.core.entity.BlogPost;

/** Mechanical editorial checks. References and examples still require human verification. */
public class BlogContentReview {
    public record Issue(String code, String message) {}
    public record Match(String id, String slug, String title, Integer status, String kind, double similarity) {}
    public record Report(int wordCount, List<Issue> blockers, List<Issue> warnings, List<Match> matches) {
        public boolean isPublishable() { return blockers.isEmpty(); }
        public boolean hasDuplicate() {
            return matches.stream().anyMatch(match -> !"SIMILAR_TITLE".equals(match.kind()));
        }
    }
    public record Facts(BlogPost post, String title, String hash, Set<String> shingles,
                        Set<String> titleWords, int words, List<Issue> quality) {}

    public Facts describe(BlogPost post, String safeHtml) {
        Document document = Jsoup.parseBodyFragment(StringUtils.defaultString(safeHtml));
        String text = normalize(document.body().text());
        List<String> tokens = words(text);
        Set<String> shingles = new HashSet<>();
        if (tokens.size() >= 80) {
            for (int i = 0; i + 5 <= tokens.size(); i++) {
                shingles.add(String.join(" ", tokens.subList(i, i + 5)));
            }
        }
        Document prose = document.clone();
        prose.select("pre, code, table").remove();
        int wordCount = words(normalize(prose.body().text())).size();
        List<Issue> quality = new ArrayList<>();
        if (StringUtils.isBlank(post.getTitle()) || StringUtils.isBlank(post.getSummary())
                || StringUtils.isBlank(post.getMetaDescription())) {
            quality.add(new Issue("MISSING_METADATA", "请补全标题、摘要和搜索描述。"));
        }
        if (wordCount < 200) {
            quality.add(new Issue("THIN_CONTENT", "正文说明不足 200 词（不含代码和表格），请补充实质内容而非凑字数。"));
        }
        if (document.select("h2, h3").size() < 2) {
            quality.add(new Issue("MISSING_STRUCTURE", "请用至少两个小节说明操作方法和适用条件。"));
        }
        if (document.select("pre, code, table, ol").stream().noneMatch(e -> !e.text().isBlank())) {
            quality.add(new Issue("MISSING_EXAMPLE", "请提供可复现的步骤、代码示例或结果对照表，并核实其正确性。"));
        }
        boolean limitations = document.select("h2, h3, h4").stream()
            .anyMatch(e -> e.text().toLowerCase(Locale.ROOT)
                .matches(".*(limitation|caveat|pitfall|troubleshoot|exception|限制|注意|误区|排查).*"));
        if (!limitations) {
            quality.add(new Issue("MISSING_LIMITATIONS", "请增加适用限制、注意事项或常见错误小节。"));
        }
        if (document.select("a[href]").stream().noneMatch(e -> externalReference(e.attr("href")))) {
            quality.add(new Issue("MISSING_SOURCE", "请补充至少一个外部 HTTPS 参考来源，并人工核实它确实支持文中结论。"));
        }
        if (document.text().matches("(?is).*\\b(TODO|TBD|insert (source|citation|example) here)\\b.*")) {
            quality.add(new Issue("PLACEHOLDER", "正文仍有待补充占位内容。"));
        }
        String title = normalize(post.getTitle());
        return new Facts(post, title, text.isEmpty() ? "" : hash(text), Set.copyOf(shingles),
            Set.copyOf(words(title)), wordCount, List.copyOf(quality));
    }

    public Report review(Facts candidate, List<Facts> corpus) {
        List<Issue> blockers = new ArrayList<>(candidate.quality());
        List<Issue> warnings = new ArrayList<>();
        List<Match> matches = new ArrayList<>();
        for (Facts existing : corpus) {
            if (candidate.post().getId() != null && Objects.equals(candidate.post().getId(), existing.post().getId())) {
                continue;
            }
            String kind = null;
            double similarity = 1;
            if (!candidate.hash().isEmpty() && candidate.hash().equals(existing.hash())) {
                kind = "SAME_CONTENT";
            } else if (!candidate.title().isEmpty() && candidate.title().equals(existing.title())) {
                kind = "SAME_TITLE";
            } else if (!candidate.shingles().isEmpty() && !existing.shingles().isEmpty()
                    && (similarity = overlap(candidate.shingles(), existing.shingles())) >= .85) {
                kind = "NEAR_CONTENT";
            } else if (candidate.titleWords().size() >= 3 && existing.titleWords().size() >= 3
                    && (similarity = overlap(candidate.titleWords(), existing.titleWords())) >= .75) {
                kind = "SIMILAR_TITLE";
            }
            if (kind != null) {
                BlogPost post = existing.post();
                matches.add(new Match(post.getId(), post.getSlug(), post.getTitle(), post.getStatus(), kind, similarity));
            }
        }
        if (matches.stream().anyMatch(m -> !"SIMILAR_TITLE".equals(m.kind()))) {
            blockers.add(new Issue("DUPLICATE", "发现相同标题、相同正文或高度相似正文，请合并到已有文章或重写后再发布。"));
        }
        if (matches.stream().anyMatch(m -> "SIMILAR_TITLE".equals(m.kind()))) {
            warnings.add(new Issue("SIMILAR_TOPIC", "存在相似选题，请确认本文提供了独立价值，优先考虑更新已有文章。"));
        }
        warnings.add(new Issue("HUMAN_REVIEW", "自动检查不验证事实、来源可信度和示例真实性；发布前请逐项人工核实。"));
        return new Report(candidate.words(), List.copyOf(blockers), List.copyOf(warnings), List.copyOf(matches));
    }

    public static String normalize(String text) {
        return Normalizer.normalize(StringUtils.defaultString(text), Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[\\p{Cf}]", "")
            .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static List<String> words(String text) {
        return text.isBlank() ? List.of() : List.of(text.split("\\s+"));
    }

    private static double overlap(Set<String> left, Set<String> right) {
        long common = left.stream().filter(right::contains).count();
        int union = left.size() + right.size() - (int) common;
        return union == 0 ? 0 : (double) common / union;
    }

    private static boolean externalReference(String href) {
        try {
            URI uri = URI.create(href);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) return false;
            host = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
            return !host.equals("whose.domains") && !host.endsWith(".whose.domains")
                && !host.equals("localhost") && host.contains(".");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
