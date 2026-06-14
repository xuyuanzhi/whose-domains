package info.wesite.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestConfig {
    
    @Autowired
    private RestTemplateBuilder builder;

	@Bean
	public RestTemplate restTemplate(ClientHttpRequestFactory factory) {
	    RestTemplate template = new RestTemplate(factory);
	    return template;
//	    template.setErrorHandler(new ResponseErrorHandler() {
//            
//            @Override
//            public boolean hasError(ClientHttpResponse response) throws IOException {
//                return false;
//            }
//            
//            @Override
//            public void handleError(ClientHttpResponse response) throws IOException {
//                
//            }
//        });
//	    List<HttpMessageConverter<?>> messageConverters = template.getMessageConverters();
//	    messageConverters.set(1, new StringHttpMessageConverter(StandardCharsets.UTF_8));
//	    template.setMessageConverters(messageConverters);
//		return builder.build();
	}
	
	@Bean
	public ClientHttpRequestFactory simpleClientHttpRequestFactory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(30000);
		factory.setReadTimeout(60000);
		return factory;
	}
}
