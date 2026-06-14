package info.wesite.core.config;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class LocaleConfig {

    @Bean
    public LocaleResolver localeResolver() {
        return new LocaleResolver() {

            @Override
            public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
            }

            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                String locale = request.getParameter("locale");
                if (StringUtils.isBlank(locale)) {
                    return Locale.getDefault();
                } else {
                    String[] split = locale.split("_");
                    return new Locale(split[0], split[1]);
                }
            }
        };
    }
}
