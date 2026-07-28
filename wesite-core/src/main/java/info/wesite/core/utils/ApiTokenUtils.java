package info.wesite.core.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 页面级 API 访问 token：页面渲染时下发、前端调 API 时回传。
 *
 * 目的：让被爬得最狠的 API 无法脱离页面直接调用。token 与客户端 IP 绑定，
 * 采集程序即使从页面 HTML 里解析出 token，换一个代理 IP 发 API 请求也会校验失败
 * ——这正是本站被刷时"页面和 XHR 来自不同 IP"的采集模式。
 *
 * 格式：{过期时间戳毫秒}.{base64url(HMAC-SHA256(secret, ip + ":" + 过期时间戳) 前16字节)}
 */
@Component
public class ApiTokenUtils {

    /**
     * token 有效期。防护主力是 IP 绑定（换代理 IP 即失效），TTL 只用于限制同 IP 的重放窗口，
     * 因此放宽到 30 分钟，避免用户在工具页停留较久后查询被误伤
     */
    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    /** 时钟余量上限：exp 超过当前时间太多的 token 必然是伪造的 */
    private static final long MAX_FUTURE_MS = 35 * 60 * 1000L;

    @Value("${app.jwt.secret:please-change-this-default-secret-key-in-production}")
    private String jwtSecret;

    private static String secret;

    @PostConstruct
    public void init() {
        secret = jwtSecret;
    }

    public static String createToken(String clientIp) {
        long exp = System.currentTimeMillis() + DEFAULT_TTL_MS;
        return exp + "." + sign(clientIp, exp);
    }

    public static boolean verifyToken(String token, String clientIp) {
        if (token == null || clientIp == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }

        long exp;
        try {
            exp = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (exp < now || exp > now + MAX_FUTURE_MS) {
            return false;
        }

        String expected = sign(clientIp, exp);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String ip, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((ip + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(sig, 16));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
