package info.wesite.core.utils;

import javax.net.ssl.SSLException;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class HttpUtils {

	protected static Logger logger = LoggerFactory.getLogger(HttpUtils.class);

	private static WebClient webClient;

	static {
		// 配置项
		HttpClient httpClient = HttpClient.create().followRedirect(true).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000).doOnConnected(
				conn -> conn.addHandlerLast(new ReadTimeoutHandler(10)).addHandlerLast(new WriteTimeoutHandler(10)))
				.httpResponseDecoder(spec -> spec.maxHeaderSize(65535)).secure(s -> {
					try {
						s.sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
								.build());
					} catch (SSLException e) {
						logger.error("Failed to configure SSL context", e);
					}
				});

		// 创建实例
		webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).defaultHeader(
				HttpHeaders.USER_AGENT,
				"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
				.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(3 * 1024 * 1024)).build();
	}
	
	public static WebClient getWebClient() {
		return webClient;
	}

	public static ResponseEntity<String> get(String uri) {
		try {
			Mono<ResponseEntity<String>> mono = webClient.get().uri(uri).retrieve().toEntity(String.class);
			return mono.block();
		} catch (Exception e) {
			logger.error("请求 {} 出错！", uri);
			return null;
		}
	}

	public static ResponseEntity<String> getAndRedirect(String uri, int maxTimes) {
		ResponseEntity<String> response = get(uri);
		if (response == null) {
			return null;
		}

		if (response.getStatusCode().is3xxRedirection() && maxTimes > 0) {
			String redirectUri = response.getHeaders().getLocation().toString();

			logger.warn("重定向到：" + redirectUri);

			if (redirectUri.startsWith("http")) {
				return getAndRedirect(redirectUri, --maxTimes);
			} else {
				return getAndRedirect(uri + redirectUri, --maxTimes);
			}
		} else {
			return response;
		}
	}

	public static void main(String[] args) {
		System.out.println(DigestUtils.md5Hex("wrduser"));
	}
}
