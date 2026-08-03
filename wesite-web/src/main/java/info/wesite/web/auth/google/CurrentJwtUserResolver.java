package info.wesite.web.auth.google;

import java.util.Optional;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.TokenUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

@Component
public class CurrentJwtUserResolver {

    private final UserService userService;

    public CurrentJwtUserResolver(UserService userService) {
        this.userService = userService;
    }

    public Optional<User> resolve(HttpServletRequest request) {
        String token = accessTokenCookie(request);
        if (token == null) {
            return Optional.empty();
        }

        User tokenUser = TokenUtils.verifyToken(token);
        if (tokenUser == null || tokenUser.getId() == null) {
            return Optional.empty();
        }

        User reloadedUser = userService.getById(tokenUser.getId());
        if (reloadedUser == null || !Integer.valueOf(BaseEntity.STATUS_ACTIVE).equals(reloadedUser.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(reloadedUser);
    }

    private String accessTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (Constants.TOKEN_KEY.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
