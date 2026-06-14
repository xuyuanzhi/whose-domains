package info.wesite.web.interceptor;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.TokenUtils;
import info.wesite.core.view.ResponseJson;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class WebInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebInterceptor.class);
    
    //静态文件版本
  	private static final String VERSION = "5." + System.currentTimeMillis();
  	
  	@Autowired
  	private Environment environment;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
    	// 以项目启动的日期作为css/js文件的版本号，解决部署后的缓存问题
		request.setAttribute("version", VERSION);
		
		request.setAttribute("requestURI", request.getRequestURI());
		
		// 是否为生产环境（用于控制Google Analytics/Ads等第三方脚本的加载）
		String[] activeProfiles = environment.getActiveProfiles();
		boolean isProd = false;
		for (String profile : activeProfiles) {
			if ("prod".equals(profile)) {
				isProd = true;
				break;
			}
		}
		request.setAttribute("_isProd", isProd);
		
		// GEO: Generate BreadcrumbList schema
		generateBreadcrumbs(request);
    			
        // get token from parameters
        String token = request.getParameter(Constants.TOKEN_KEY);

        // get token from request header
        if (StringUtils.isBlank(token)) {
            token = request.getHeader(Constants.TOKEN_KEY);
        }

        // get token from cookie
        if (StringUtils.isBlank(token)) {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie c : cookies) {
                    if (Constants.TOKEN_KEY.equals(c.getName())) {
                        token = c.getValue();
                        break;
                    }
                }
            }
        }

        // verify token
        if (StringUtils.isNotBlank(token)) {
            User user = TokenUtils.verifyToken(token);
            if (user != null) {
                UserHolder.set(user);
                request.setAttribute("user", user);
            }
        }
        
        // 设置默认访问限制
        AccessControl.Level level = AccessControl.Level.NONE;
        if (handler instanceof HandlerMethod handlerMethod) {
            final Class<?> clazz = handlerMethod.getBeanType();

            if (handlerMethod.getMethod().isAnnotationPresent(AccessControl.class)) {
                level = handlerMethod.getMethod().getDeclaredAnnotation(AccessControl.class).level();
            } else if (clazz.isAnnotationPresent(AccessControl.class)) {
                level = clazz.getDeclaredAnnotation(AccessControl.class).level();
            }
        }

        // invoke controller method
        if (level == AccessControl.Level.NONE || UserHolder.get() != null) {
            return true;
        }

        if (isAjax(request)) {
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write(JSON.toJSONString(ResponseJson.response(ResponseJson.CODE_NOAUTH, "请登录")));
            response.getWriter().flush();
        } else {
            response.sendRedirect("/");
        }

        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
        UserHolder.remove();
    }

    private boolean isAjax(HttpServletRequest request) {
        return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }

    /**
     * Generate BreadcrumbList JSON-LD schema based on the request URI.
     */
    private void generateBreadcrumbs(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("/".equals(uri)) return;

        // Breadcrumb name mappings
        java.util.Map<String, String> nameMap = new java.util.LinkedHashMap<>();
        nameMap.put("/tools", "Tools");
        nameMap.put("/tools/whois-lookup", "WHOIS Lookup");
        nameMap.put("/tools/rdap-lookup", "RDAP Lookup");
        nameMap.put("/tools/domain-availability", "Domain Availability");
        nameMap.put("/tools/bulk-domain-search", "Bulk Domain Search");
        nameMap.put("/tools/whois-compare", "WHOIS Compare");
        nameMap.put("/tools/related-domains", "Related Domains");
        nameMap.put("/tools/domain-analyzer", "Domain Analyzer");
        nameMap.put("/tools/domain-score", "Domain Health Score");
        nameMap.put("/tools/dns-analyzer", "DNS Analyzer");
        nameMap.put("/tools/ssl-checker", "SSL Checker");
        nameMap.put("/tools/domain-history", "WHOIS History");
        nameMap.put("/tools/reverse-ip", "Reverse IP Lookup");
        nameMap.put("/tools/competitor-analysis", "Competitor Analysis");
        nameMap.put("/tools/my-ip-address", "My IP Address");
        nameMap.put("/tools/json-formatter", "JSON Formatter");
        nameMap.put("/tools/timezone-converter", "Time Zone Converter");
        nameMap.put("/top-level-domains", "Top Level Domains");
        nameMap.put("/expiring-domains", "Expiring Domains");
        nameMap.put("/api-docs", "API Documentation");
        nameMap.put("/about-us", "About Us");
        nameMap.put("/contact-us", "Contact Us");
        nameMap.put("/help-center", "Help Center");
        nameMap.put("/privacy-policy", "Privacy Policy");
        nameMap.put("/terms-of-service", "Terms of Service");
        // Info/Knowledge Base articles
        nameMap.put("/info/what-is-a-domain-name", "What is a Domain Name?");
        nameMap.put("/info/domain-structure-and-tlds", "Domain Structure and TLDs");
        nameMap.put("/info/how-domain-registration-works", "How Domain Registration Works");
        nameMap.put("/info/understanding-domain-ownership", "Understanding Domain Ownership");
        nameMap.put("/info/what-is-whois", "What is WHOIS?");
        nameMap.put("/info/how-to-perform-a-whois-lookup", "How to Perform a WHOIS Lookup");
        nameMap.put("/info/reading-whois-results", "Reading WHOIS Results");
        nameMap.put("/info/domain-privacy-protection", "Domain Privacy Protection");
        nameMap.put("/info/why-domain-history-matters", "Why Domain History Matters");
        nameMap.put("/info/tracking-ownership-changes", "Tracking Ownership Changes");
        nameMap.put("/info/dns-record-history", "DNS Record History");
        nameMap.put("/info/expiration-and-renewal-history", "Expiration and Renewal History");
        nameMap.put("/info/domain-security-basics", "Domain Security Basics");
        nameMap.put("/info/domain-lock-and-transfer-protection", "Domain Lock and Transfer Protection");
        nameMap.put("/info/understanding-dnssec", "Understanding DNSSEC");
        nameMap.put("/info/preventing-domain-fraud", "Preventing Domain Fraud");

        JSONArray itemList = new JSONArray();
        int position = 1;
        
        // Always add Home
        JSONObject home = new JSONObject();
        home.put("@type", "ListItem");
        home.put("position", position++);
        home.put("name", "Home");
        home.put("item", "https://whose.domains/");
        itemList.add(home);
        
        // Build breadcrumb path
        if (uri.startsWith("/tools/")) {
            JSONObject toolsItem = new JSONObject();
            toolsItem.put("@type", "ListItem");
            toolsItem.put("position", position++);
            toolsItem.put("name", "Tools");
            toolsItem.put("item", "https://whose.domains/tools/whois-lookup");
            itemList.add(toolsItem);
        } else if (uri.startsWith("/info/")) {
            JSONObject helpItem = new JSONObject();
            helpItem.put("@type", "ListItem");
            helpItem.put("position", position++);
            helpItem.put("name", "Help Center");
            helpItem.put("item", "https://whose.domains/help-center");
            itemList.add(helpItem);
        }
        
        String pageName = nameMap.get(uri);
        if (pageName != null) {
            JSONObject pageItem = new JSONObject();
            pageItem.put("@type", "ListItem");
            pageItem.put("position", position++);
            pageItem.put("name", pageName);
            pageItem.put("item", "https://whose.domains" + uri);
            itemList.add(pageItem);
        } else if (uri.startsWith("/domain/")) {
            String domain = uri.substring("/domain/".length());
            JSONObject pageItem = new JSONObject();
            pageItem.put("@type", "ListItem");
            pageItem.put("position", position++);
            pageItem.put("name", domain + " WHOIS");
            pageItem.put("item", "https://whose.domains" + uri);
            itemList.add(pageItem);
        }
        
        if (itemList.size() > 1) {
            JSONObject schema = new JSONObject();
            schema.put("@context", "https://schema.org");
            schema.put("@type", "BreadcrumbList");
            schema.put("itemListElement", itemList);
            request.setAttribute("_breadcrumbs", true);
            request.setAttribute("_breadcrumbSchema", schema.toJSONString());
        }
    }
}