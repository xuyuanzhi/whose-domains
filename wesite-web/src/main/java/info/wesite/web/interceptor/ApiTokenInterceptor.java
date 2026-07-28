package info.wesite.web.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.utils.ApiTokenUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.view.ResponseJson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 校验页面下发的短时效 API token（X-Api-Token 头），
 * 只挂在被采集重灾区的端点上（见 InterceptorConfig）。
 * token 与 IP 绑定，脱离页面、或换代理 IP 的直接调用会被拒绝。
 */
@Component
public class ApiTokenInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String token = request.getHeader("X-Api-Token");
        String ip = IpUtils.getRequestIp(request);

        if (ApiTokenUtils.verifyToken(token, ip)) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(JSON.toJSONString(
                ResponseJson.failure("Invalid or expired request token. Please refresh the page and try again.")));
        response.getWriter().flush();
        return false;
    }
}
