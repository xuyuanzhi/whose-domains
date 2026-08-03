package info.wesite.web.auth;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import info.wesite.core.entity.User;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.TokenUtils;

@Service
public class AuthCookieService {

    @Value("${wesite.auth-cookie-secure:true}")
    private boolean authCookieSecure = true;

    public ResponseCookie create(User user) {
        return authCookie(TokenUtils.createToken(user, 60 * 24 * 30), Duration.ofDays(30));
    }

    public ResponseCookie clear() {
        return authCookie("", Duration.ZERO);
    }

    private ResponseCookie authCookie(String token, Duration maxAge) {
        return ResponseCookie.from(Constants.TOKEN_KEY, token)
                .httpOnly(true).secure(authCookieSecure).sameSite("Lax").path("/").maxAge(maxAge).build();
    }
}
