package info.wesite.web.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 安全响应头过滤器，为所有 HTTP 响应添加安全相关的头信息
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeaderFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 静态资源跳过安全头设置，减少性能开销
        if (request instanceof jakarta.servlet.http.HttpServletRequest httpRequest) {
            String uri = httpRequest.getRequestURI();
            if (uri.startsWith("/static/")) {
                chain.doFilter(request, response);
                return;
            }
        }

        // 防止点击劫持
        httpResponse.setHeader("X-Frame-Options", "SAMEORIGIN");

        // 防止 MIME 类型嗅探
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");

        // 启用 XSS 过滤
        httpResponse.setHeader("X-XSS-Protection", "1; mode=block");

        // 强制 HTTPS（HSTS）- 1年
        httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        // 控制 Referrer 信息泄露
        httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // 权限策略
        httpResponse.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");

        // Content Security Policy - 允许 Google Analytics, Google Ads, 自身资源
        httpResponse.setHeader("Content-Security-Policy",
                "default-src 'self'; "
                + "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://pagead2.googlesyndication.com https://www.google-analytics.com https://static.cloudflareinsights.com https://fundingchoicesmessages.google.com https://ep2.adtrafficquality.google; "
                + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                + "img-src 'self' data: https:; "
                + "font-src 'self' data: https://fonts.gstatic.com; "
                + "connect-src 'self' https://www.google-analytics.com https://*.googlesyndication.com https://ep1.adtrafficquality.google https://fundingchoicesmessages.google.com; "
                + "frame-src https://pagead2.googlesyndication.com https://tpc.googlesyndication.com https://googleads.g.doubleclick.net https://ep2.adtrafficquality.google https://www.google.com;");

        chain.doFilter(request, response);
    }
}
