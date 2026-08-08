package info.wesite.web.seo;

import org.springframework.stereotype.Service;

@Service
public class CanonicalUrlService {

    public static final String ORIGIN = "https://whose.domains";

    public String normalizePath(String requestUri) {
        String path = requestUri == null || requestUri.isBlank() ? "/" : requestUri;
        path = path.replaceAll("/{2,}", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    public String canonicalUrl(String requestUri) {
        return ORIGIN + normalizePath(requestUri);
    }
}
