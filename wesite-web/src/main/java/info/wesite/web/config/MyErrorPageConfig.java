package info.wesite.web.config;

import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.ErrorPageRegistrar;
import org.springframework.boot.web.server.ErrorPageRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MyErrorPageConfig implements ErrorPageRegistrar {

	@Override
	public void registerErrorPages(ErrorPageRegistry registry) {
		// 当发生404错误时，系统会转发到"/my404"这个路径
        ErrorPage error404Page = new ErrorPage(HttpStatus.NOT_FOUND, "/404");
        // 同样可以注册500等其他错误页面
        ErrorPage error500Page = new ErrorPage(HttpStatus.INTERNAL_SERVER_ERROR, "/500");
        registry.addErrorPages(error404Page, error500Page);
	}

}
