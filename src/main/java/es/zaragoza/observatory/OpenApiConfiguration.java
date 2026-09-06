package es.zaragoza.observatory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

/**
 * Metadatos del contrato OpenAPI de la API propia (SPEC.md §4.7, §5 «atribución»). El documento se genera en
 * {@code /v3/api-docs} y la interfaz en {@code /swagger-ui.html}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	@Bean
	OpenAPI observatorioOpenApi() {
		return new OpenAPI().info(new Info()
				.title("Observatorio de Datos Abiertos de Zaragoza")
				.version("v1")
				.description("API de lectura pública sobre los datos abiertos del Ayuntamiento de Zaragoza "
						+ "(https://www.zaragoza.es/sede/portal/datos-abiertos/). Cada respuesta indica el dataset de "
						+ "origen (source), la fecha de ingesta (ingestedAt) y las advertencias aplicables (caveats). "
						+ "Herramienta de análisis, no de conclusiones.")
				.license(new License().name("Datos: licencia de reutilización del Ayuntamiento de Zaragoza")
						.url("https://www.zaragoza.es/sede/portal/aviso-legal#condiciones")));
	}

}
