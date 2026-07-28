package info.wesite.web.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import info.wesite.web.interceptor.ApiTokenInterceptor;
import info.wesite.web.interceptor.WebInterceptor;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

	@Autowired
	private WebInterceptor webInterceptor;

	@Autowired
	private ApiTokenInterceptor apiTokenInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(webInterceptor).addPathPatterns("/**").excludePathPatterns("/static/**",
				"/error", "/swagger-ui.html", "/swagger-ui/**", "/v3/**", "/doc.html");

		// 页面 token 校验：只保护被批量采集的数据端点，其余公开 API 不受影响
		registry.addInterceptor(apiTokenInterceptor).addPathPatterns(
				"/api/domain-history/**",
				"/api/tools/score/**",
				"/api/tools/related/**");
	}

	/**
	 * Spring Boot 2.6+ 默认关闭尾斜杠匹配，这里重新开启。
	 * 使得 /tools 和 /tools/ 都能命中 @GetMapping({"","/"}) 。
	 */
	@Override
	public void configurePathMatch(PathMatchConfigurer configurer) {
		configurer.setUseTrailingSlashMatch(true);
	}

}