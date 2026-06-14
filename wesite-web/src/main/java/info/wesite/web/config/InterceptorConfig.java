package info.wesite.web.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import info.wesite.web.interceptor.WebInterceptor;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

	@Autowired
	private WebInterceptor webInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(webInterceptor).addPathPatterns("/**").excludePathPatterns("/static/**",
				"/error", "/swagger-ui.html", "/swagger-ui/**", "/v3/**", "/doc.html");
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