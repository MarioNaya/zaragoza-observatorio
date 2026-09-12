package es.zaragoza.observatory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * ADR-020 §3: el frontend se sirve desde otro dominio, así que la API tiene que dejarse leer desde el
 * navegador. Lo que se comprueba aquí es <b>qué</b> se abre y qué no.
 * <p>
 * El comodín es deliberado y no relaja nada: la API es GET público sin credenciales. La prueba que lo sostiene
 * es la de {@code allowCredentials}, porque es la que garantiza que el navegador no adjunta cookies y que, por
 * tanto, abrir el origen no abre ninguna sesión.
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false",
		"zaragoza.catalog.observation.enabled=false" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class WebCorsTests {

	private static final String ORIGIN = "https://observatorio.example.org";

	@Autowired
	MockMvcTester mvc;

	@Test
	void theReadApiCanBeCalledFromABrowserOnAnotherDomain() {
		var preflight = mvc.options().uri("/api/v1/territory/districts")
				.header(HttpHeaders.ORIGIN, ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET").exchange();

		assertThat(preflight.getResponse().getStatus()).isEqualTo(200);
		assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);
		assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
				.as("solo lectura: no hay escritura en la API hasta la fase 5").contains("GET").doesNotContain("POST");
		assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
				.as("sin credenciales, que es lo que hace inofensivo el comodín").isNull();
	}

	@Test
	void aSimpleGetCarriesTheAllowOriginHeader() {
		var response = mvc.get().uri("/api/v1/geo/districts").header(HttpHeaders.ORIGIN, ORIGIN).exchange()
				.getResponse();

		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);
	}

	@Test
	void theContractIsReadableTooBecauseItIsPartOfTheProduct() {
		var response = mvc.get().uri("/v3/api-docs").header(HttpHeaders.ORIGIN, ORIGIN).exchange().getResponse();

		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);
	}

	/**
	 * El actuator no se abre. En producción solo expone {@code health} e {@code info} (ADR-008 §6), pero eso es
	 * una decisión de despliegue: que además no sea legible desde otro origen es una segunda cerradura.
	 */
	@Test
	void theActuatorIsNotOpenedToOtherOrigins() {
		var preflight = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.options("/actuator/health").header(HttpHeaders.ORIGIN, ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()));

		assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
	}

}
