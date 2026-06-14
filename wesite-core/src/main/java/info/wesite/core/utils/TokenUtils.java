package info.wesite.core.utils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

import info.wesite.core.entity.User;
import jakarta.annotation.PostConstruct;

@Component
public class TokenUtils {

    private static final Logger logger = LoggerFactory.getLogger(TokenUtils.class);

    @Value("${app.jwt.secret:please-change-this-default-secret-key-in-production}")
    private String jwtSecret;

    @Value("${app.jwt.issuer:system}")
    private String jwtIssuer;

    private static String secret;
    private static String issuer;

    @PostConstruct
    public void init() {
        secret = jwtSecret;
        issuer = jwtIssuer;
    }

    public static String createToken(User user, int minutes) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        String token = JWT.create().withIssuer(issuer)
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plus(minutes, ChronoUnit.MINUTES))
                .withClaim("userId", user.getId())
                .withClaim("userName", user.getName())
                .withClaim("phoneNo", user.getPhoneNo())
                .sign(algorithm);
        return token;
    }

    public static User verifyToken(String token) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        JWTVerifier verifier = JWT.require(algorithm).withIssuer(issuer).build();
        try {
            DecodedJWT jwt = verifier.verify(token);
            if (jwt != null) {
                String userId = jwt.getClaim("userId").asString();
                String userName = jwt.getClaim("userName").asString();
                String phoneNo = jwt.getClaim("phoneNo").asString();
                if (StringUtils.isNotBlank(userId)) {
                    User user = new User();
                    user.setId(userId);
                    user.setName(userName);
                    user.setPhoneNo(phoneNo);
                    return user;
                }
            }
        } catch (Exception e) {
            logger.error("Token verification failed: {}", e.getMessage());
        }

        return null;
    }

}