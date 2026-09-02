package info.wesite.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import info.wesite.admin.interceptor.AdminInterceptor;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

	private final AdminInterceptor adminInterceptor;

	public InterceptorConfig(AdminInterceptor adminInterceptor) {
		this.adminInterceptor = adminInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminInterceptor)
				.addPathPatterns("/**")
				.excludePathPatterns("/static/**", "/error");
	}

}
