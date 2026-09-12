package es.zaragoza.observatory;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Acceso desde otro origen a la API de lectura (ADR-020).
 * <p>
 * El valor por defecto es {@code *} y eso <b>no</b> es una relajación: toda la API es GET público sin
 * autenticación, sin cookies y sin cabecera de sesión (SPEC.md §4.7), así que un navegador no puede leer por
 * esta vía nada que no pueda leer un {@code curl} anónimo. Negarlo solo impediría que la API fuera reutilizable
 * desde el navegador, que es justamente lo que SPEC.md §6 pide que sea. El frontend propio se sirve desde otro
 * dominio (ADR-020 §3), de modo que sin esto no habría producto, pero la razón de abrirlo no es esa: es que el
 * dato es público.
 * <p>
 * La lista se puede estrechar sin tocar código con {@code zaragoza.web.cors.allowed-origins}. Nunca se envían
 * credenciales: con {@code allowCredentials} a {@code false}, el comodín es legal y el navegador no adjunta
 * cookies, que es la propiedad que hace inofensiva la apertura.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebCorsConfiguration.CorsProperties.class)
class WebCorsConfiguration {

	/** Lo que se abre: la API de lectura y el contrato. Nada más, y desde luego no el actuator. */
	private static final String[] PUBLIC_READ_PATHS = { "/api/v1/**", "/v3/api-docs/**" };

	@Bean
	WebMvcConfigurer corsConfigurer(CorsProperties properties) {
		return new WebMvcConfigurer() {

			@Override
			public void addCorsMappings(CorsRegistry registry) {
				for (String path : PUBLIC_READ_PATHS) {
					registry.addMapping(path)
							.allowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new))
							// Solo lectura: la API no tiene escritura hasta la fase 5 (workspace).
							.allowedMethods("GET", "HEAD", "OPTIONS")
							.allowedHeaders("*")
							// Deja leer al navegador las cabeceras que el producto publica de verdad.
							.exposedHeaders("Content-Length", "Last-Modified", "ETag")
							.allowCredentials(false)
							.maxAge(properties.maxAgeSeconds());
				}
			}
		};
	}

	/**
	 * @param allowedOrigins patrones de origen admitidos; {@code *} por defecto porque el dato es público
	 * @param maxAgeSeconds cuánto puede cachear el navegador la respuesta al preflight
	 */
	@ConfigurationProperties("zaragoza.web.cors")
	record CorsProperties(List<String> allowedOrigins, long maxAgeSeconds) {

		CorsProperties {
			if (allowedOrigins == null || allowedOrigins.isEmpty()) {
				allowedOrigins = List.of("*");
			}
			allowedOrigins = List.copyOf(allowedOrigins);
			if (maxAgeSeconds <= 0) {
				maxAgeSeconds = 3600;
			}
		}
	}

}
