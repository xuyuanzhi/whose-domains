package info.wesite.admin.interceptor;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.TokenUtils;
import info.wesite.core.view.ResponseJson;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AdminInterceptor implements HandlerInterceptor {

    private static final String LAYUI_TOKEN_KEY = "access_token";

    private final UserService userService;

    public AdminInterceptor(UserService userService) {
        this.userService = userService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        UserHolder.remove();
        if (accessLevel(handler) == AccessControl.Level.NONE) {
            return true;
        }

        User identity = verifyIdentity(extractToken(request));
        User currentUser = identity == null ? null : userService.getById(identity.getId());
        if (!isActiveAdmin(currentUser)) {
            return reject(request, response, handler);
        }

        UserHolder.set(currentUser);
        request.setAttribute("user", currentUser);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.remove();
    }

    private static AccessControl.Level accessLevel(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return AccessControl.Level.NONE;
        }
        AccessControl methodControl = AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getMethod(), AccessControl.class);
        if (methodControl != null) {
            return methodControl.level();
        }
        AccessControl typeControl = AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getBeanType(), AccessControl.class);
        return typeControl == null ? AccessControl.Level.SESSION : typeControl.level();
    }

    private static String extractToken(HttpServletRequest request) {
        String token = firstNonBlank(
            request.getParameter(Constants.TOKEN_KEY),
            request.getHeader(Constants.TOKEN_KEY),
            request.getParameter(LAYUI_TOKEN_KEY),
            request.getHeader(LAYUI_TOKEN_KEY));
        if (StringUtils.isNotBlank(token)) {
            return token;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ((Constants.TOKEN_KEY.equals(cookie.getName())
                        || LAYUI_TOKEN_KEY.equals(cookie.getName()))
                        && StringUtils.isNotBlank(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static User verifyIdentity(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        User identity = TokenUtils.verifyToken(token);
        return identity != null && StringUtils.isNotBlank(identity.getId()) ? identity : null;
    }

    private static boolean isActiveAdmin(User user) {
        return user != null
            && Objects.equals(user.getStatus(), User.STATUS_ACTIVE)
            && (user.getDeleted() == null || user.getDeleted() == 0)
            && User.TYPE_ADMIN.equals(user.getUserType());
    }

    private static boolean reject(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {
        UserHolder.remove();
        if (isJsonHandler(handler)) {
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSON.toJSONString(
                ResponseJson.response(ResponseJson.CODE_NOAUTH, "请登录")));
            response.getWriter().flush();
        } else {
            response.sendRedirect(request.getContextPath() + "/");
        }
        return false;
    }

    private static boolean isJsonHandler(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), ResponseBody.class)
            || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), ResponseBody.class);
    }
}
