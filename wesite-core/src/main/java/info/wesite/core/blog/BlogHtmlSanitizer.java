package info.wesite.core.blog;

import java.net.URI;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class BlogHtmlSanitizer {

    private static final Safelist ALLOWED = new BlogSafelist();

    private static final Document.OutputSettings OUTPUT_SETTINGS =
        new Document.OutputSettings().prettyPrint(false);

    public String sanitize(String html) {
        if (StringUtils.isBlank(html)) {
            return "";
        }
        return Jsoup.clean(html, "", ALLOWED, OUTPUT_SETTINGS);
    }

    public boolean hasVisibleContent(String sanitizedHtml) {
        if (StringUtils.isBlank(sanitizedHtml)) {
            return false;
        }
        String text = Jsoup.parseBodyFragment(sanitizedHtml).body().text();
        return text.codePoints()
            .anyMatch(codePoint -> !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint));
    }

    private static final class BlogSafelist extends Safelist {

        private BlogSafelist() {
            addTags(
                "p", "h2", "h3", "h4",
                "ul", "ol", "li",
                "strong", "em", "code", "pre", "blockquote", "br", "a",
                "table", "thead", "tbody", "tr", "th", "td");
            addAttributes("a", "href", "title");
            addProtocols("a", "href", "http", "https", "mailto");
            preserveRelativeLinks(true);
        }

        @Override
        public boolean isSafeAttribute(String tagName, Element element, Attribute attribute) {
            if ("a".equals(tagName)
                    && "href".equalsIgnoreCase(attribute.getKey())
                    && isSafeRelativeHref(attribute.getValue())) {
                return true;
            }
            return super.isSafeAttribute(tagName, element, attribute);
        }

        private static boolean isSafeRelativeHref(String value) {
            String href = StringUtils.trimToEmpty(value);
            if (href.isEmpty() || href.startsWith("//") || href.startsWith("\\")) {
                return false;
            }
            try {
                URI uri = URI.create(href);
                return !uri.isAbsolute() && uri.getRawAuthority() == null;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
    }
}
