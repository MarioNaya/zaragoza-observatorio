package es.zaragoza.observatory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP único de la aplicación hacia la infraestructura municipal, como el {@link java.time.Clock}: lo
 * construye el {@code RestClient.Builder} de Boot (aplica {@code spring.http.clients.*}: timeouts y no seguir
 * redirecciones, S0.5) con la identificación del reutilizador (SPEC.md §2.2). {@code ingestion} le añade
 * reintentos y circuit breaker (ADR-004); {@code catalog} lo usa tal cual para observar distribuciones (S1.1).
 * Un solo cliente permite además que los tests lo sustituyan por {@code MockRestServiceServer}.
 */
@Configuration(proxyBeanMethods = false)
class HttpClientConfiguration {

	@Bean
	RestClient zaragozaRestClient(RestClient.Builder builder,
			@Value("${zaragoza.http.user-agent:observatorio-zaragoza/0.0.1 (+https://github.com/MarioNaya/zaragoza-observatorio)}") String userAgent) {
		return builder.defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
	}

}
