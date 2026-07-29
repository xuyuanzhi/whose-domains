package info.wesite.core.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 页面级 API 访问 token：页面渲染时下发、前端调 API 时回传。
 *
 * 目的：让被爬得最狠的 API 无法脱离页面直接调用。token 与客户端所在网段（IPv4 /24、IPv6 /48）
 * 绑定，采集程序即使从页面 HTML 里解析出 token，用另一网段的代理 IP 发 API 请求也会校验失败
 * ——正是本站被刷时"页面和 XHR 来自不同网段"的采集模式。绑定到网段而非精确 IP，可容忍正常
 * 用户在同一运营商/出口内的地址微变，减少误伤。
 *
 * 格式：{过期时间戳毫秒}.{base64url(HMAC-SHA256(secret, subnet + ":" + 过期时间戳) 前16字节)}
 */
@Component
public class ApiTokenUtils {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenUtils.class);

    private static final String DEFAULT_SECRET = "please-change-this-default-secret-key-in-production";

    /**
     * token 有效期。防护主力是网段绑定（换到别的网段即失效），TTL 只用于限制重放窗口，
     * 放宽到 30 分钟，避免用户在工具页停留较久后查询被误伤。
     */
    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    /** 允许的时钟偏移。派生自 TTL，避免二者独立调整时漂移导致新签发的 token 被判过期。 */
    private static final long CLOCK_SKEW_MS = 5 * 60 * 1000L;
    private static final long MAX_FUTURE_MS = DEFAULT_TTL_MS + CLOCK_SKEW_MS;

    @Value("${app.jwt.secret:" + DEFAULT_SECRET + "}")
    private String jwtSecret;

    private final Environment environment;

    private static String secret;

    public ApiTokenUtils(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        secret = jwtSecret;
        if (DEFAULT_SECRET.equals(secret)) {
            boolean isProd = false;
            for (String p : environment.getActiveProfiles()) {
                if ("prod".equals(p)) {
                    isProd = true;
                    break;
                }
            }
            if (isProd) {
                throw new IllegalStateException(
                        "app.jwt.secret 仍为默认值，生产环境拒绝启动：请设置 JWT_SECRET 环境变量。"
                        + "默认密钥在公开仓库可见，会导致 API token 可被离线伪造。");
            }
            log.error("[SECURITY] app.jwt.secret 仍为默认值，API token 可被伪造。生产环境务必设置 JWT_SECRET。");
        }
    }

    public static String createToken(String clientIp) {
        long exp = System.currentTimeMillis() + DEFAULT_TTL_MS;
        return exp + "." + sign(bindingKey(clientIp), exp);
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

        String expected = sign(bindingKey(clientIp), exp);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
    }

    /** token 绑定到客户端所在网段，而非精确 IP */
    private static String bindingKey(String clientIp) {
        return IpUtils.subnetId(clientIp);
    }

    private static String sign(String data, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((data + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(sig, 16));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
