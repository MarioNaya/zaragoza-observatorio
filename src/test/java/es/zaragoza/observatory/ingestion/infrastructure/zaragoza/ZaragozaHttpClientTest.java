package es.zaragoza.observatory.ingestion.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.ingestion.domain.SourceAccessException;
import es.zaragoza.observatory.ingestion.domain.SourceAccessException.Kind;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;
import es.zaragoza.observatory.support.Fixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador HTTP sobre {@link MockRestServiceServer} con respuestas reales grabadas (ADR-004). Cubre las reglas
 * de S0.5: URL con extensión y parámetros, clasificación de errores, reintentos solo en fallos reintentables,
 * circuit breaker por dataset y {@code Last-Modified} con zona {@code CET}.
 */
class ZaragozaHttpClientTest {

	static final String CATALOG_URL = "https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json";
	static final DatasetRef CATALOG = DatasetRef.of(Sources.DATA_SPACE, "catalogo");
	static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");
	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	MockRestServiceServer server;
	ZaragozaHttpClient client;
	CircuitBreakerRegistry breakers;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		Retry retry = Retry.of("test", RetryConfig.custom()
				.maxAttempts(3)
				.waitDuration(Duration.ofMillis(1))
				.retryOnException(ZaragozaHttpClient::isRetryable)
				.build());
		// Ventana amplia para que los tests de reintento (3 intentos) no abran el circuito; el test del circuito
		// encadena varias peticiones hasta alcanzar el mínimo de llamadas.
		breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
				.slidingWindowSize(10)
				.minimumNumberOfCalls(10)
				.failureRateThreshold(50)
				.waitDurationInOpenState(Duration.ofMinutes(1))
				.recordException(ZaragozaHttpClient::isRetryable)
				.build());
		client = new ZaragozaHttpClient(builder.build(), JsonMapper.shared(), retry, breakers, new Semaphore(4),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void fetchesAnEnvelopePageWithRealCatalogResponse() {
		server.expect(requestTo(startsWith(CATALOG_URL + "?")))
				.andExpect(method(HttpMethod.GET))
				.andExpect(queryParam("fl", "id,title,formato"))
				.andExpect(queryParam("rows", "2"))
				.andExpect(queryParam("start", "0"))
				.andRespond(withSuccess(Fixtures.bytes("catalog/catalogo-rows2-fl.json"), JSON_UTF8)
						.header(HttpHeaders.LAST_MODIFIED, "Tue, 20 Jan 2026 13:12:38 CET"));

		RawPage page = client.fetch(catalog(Pagination.offset(2)), 0, 0);

		server.verify();
		assertThat(page.number()).isZero();
		assertThat(page.start()).isZero();
		assertThat(page.recordCount()).isEqualTo(2);
		assertThat(page.totalCount()).isEqualTo(436);
		assertThat(page.sourceLastModified()).isEqualTo(Instant.parse("2026-01-20T12:12:38Z"));
		assertThat(page.etag()).isNull();
		assertThat(page.contentType()).contains("json");
		assertThat(page.body()).contains("\"totalCount\":436").contains("Arte Público");
		assertThat(page.fetchedAt()).isEqualTo(NOW);
		assertThat(page.url().toString()).startsWith(CATALOG_URL + "?fl=");
	}

	@Test
	void countsArrayShapeAndOmitsStartWhenNotPaginated() {
		server.expect(requestTo("https://www.zaragoza.es/api/recurso/open311/requests.json?rows=1000"))
				.andRespond(withSuccess("[{\"service_request_id\":\"1\"},{\"service_request_id\":\"2\"}]",
						MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"abc\""));
		var source = new SourceDescriptor(DatasetRef.of(Sources.OPEN311, "requests"),
				URI.create("https://www.zaragoza.es/api/recurso/open311/requests.json"), Map.of(),
				Pagination.none(1000), ResponseShape.ARRAY);

		RawPage page = client.fetch(source, 0, 0);

		assertThat(page.recordCount()).isEqualTo(2);
		assertThat(page.totalCount()).isNull();
		assertThat(page.etag()).isEqualTo("\"abc\"");
		assertThat(page.sourceLastModified()).isNull();
	}

	@Test
	void retriesServerErrorsAndSucceeds() {
		server.expect(requestTo(startsWith(CATALOG_URL))).andRespond(withServerError());
		server.expect(requestTo(startsWith(CATALOG_URL))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
		server.expect(requestTo(startsWith(CATALOG_URL)))
				.andRespond(withSuccess(Fixtures.bytes("catalog/catalogo-rows2-fl.json"), JSON_UTF8));

		RawPage page = client.fetch(catalog(Pagination.offset(2)), 0, 0);

		server.verify();
		assertThat(page.recordCount()).isEqualTo(2);
	}

	@Test
	void retriesIoErrorsAndTimeoutsUntilExhausted() {
		server.expect(times(3), requestTo(startsWith(CATALOG_URL)))
				.andRespond(withException(new IOException("connection reset")));

		assertThatThrownBy(() -> client.fetch(catalog(Pagination.offset(2)), 0, 0))
				.isInstanceOfSatisfying(SourceAccessException.class, ex -> {
					assertThat(ex.kind()).isEqualTo(Kind.IO);
					assertThat(ex.retryable()).isTrue();
					assertThat(ex.status()).isNull();
				});
		server.verify();
	}

	@Test
	void doesNotRetryNotFoundJson() {
		server.expect(times(1), requestTo(startsWith("https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo/999999.json")))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.body(Fixtures.bytes("catalog/catalogo-999999-notfound.json"))
						.contentType(JSON_UTF8));
		var source = new SourceDescriptor(DatasetRef.of(Sources.DATA_SPACE, "catalogo/999999"),
				URI.create("https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo/999999.json"), Map.of(),
				Pagination.none(1), ResponseShape.ENVELOPE);

		assertThatThrownBy(() -> client.fetch(source, 0, 0))
				.isInstanceOfSatisfying(SourceAccessException.class, ex -> {
					assertThat(ex.kind()).isEqualTo(Kind.NOT_FOUND);
					assertThat(ex.status()).isEqualTo(404);
					assertThat(ex.retryable()).isFalse();
					assertThat(ex.getMessage()).contains("Registro no encontrado");
				});
		server.verify();
		assertThat(breakers.circuitBreaker("data-space:catalogo/999999").getState())
				.isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void doesNotRetryBadRequest() {
		server.expect(times(1), requestTo(startsWith(CATALOG_URL)))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST)
						.body("{\"status\":400,\"mensaje\":\"Failed to convert value of type\"}")
						.contentType(JSON_UTF8));

		assertThatThrownBy(() -> client.fetch(catalog(Pagination.offset(2)), 0, 0))
				.isInstanceOfSatisfying(SourceAccessException.class,
						ex -> assertThat(ex.kind()).isEqualTo(Kind.CLIENT_ERROR));
		server.verify();
	}

	@Test
	void treatsHtmlBodiesAsRetryableMalformedResponses() {
		server.expect(times(3), requestTo(startsWith(CATALOG_URL)))
				.andRespond(withSuccess("<html><body>WebLogic</body></html>", MediaType.TEXT_HTML));

		assertThatThrownBy(() -> client.fetch(catalog(Pagination.offset(2)), 0, 0))
				.isInstanceOfSatisfying(SourceAccessException.class, ex -> {
					assertThat(ex.kind()).isEqualTo(Kind.MALFORMED);
					assertThat(ex.getMessage()).contains("text/html");
				});
		server.verify();
	}

	@Test
	void treatsRedirectsAsMalformed() {
		server.expect(times(3), requestTo(startsWith(CATALOG_URL)))
				.andRespond(withStatus(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, "https://www.zaragoza.es/x"));

		assertThatThrownBy(() -> client.fetch(catalog(Pagination.offset(2)), 0, 0))
				.isInstanceOfSatisfying(SourceAccessException.class, ex -> {
					assertThat(ex.kind()).isEqualTo(Kind.MALFORMED);
					assertThat(ex.status()).isEqualTo(303);
				});
	}

	@Test
	void opensTheCircuitPerDatasetAfterRepeatedFailures() {
		// 10 llamadas fallidas (mínimo de la ventana): 3 fetch x 3 intentos + el primer intento del cuarto fetch
		server.expect(times(10), requestTo(startsWith(CATALOG_URL))).andRespond(withServerError());

		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> client.fetch(catalog(Pagination.offset(2)), 0, 0))
					.isInstanceOfSatisfying(SourceAccessException.class,
							ex -> assertThat(ex.kind()).isEqualTo(Kind.SERVER_ERROR));
			assertThat(client.breakerState(CATALOG.key())).isEqualTo(CircuitBreaker.State.CLOSED);
		}
		assertThatThrownBy(() -> client.fetch(catalog(Pagination.offset(2)), 0, 0))
				.isInstanceOfSatisfying(SourceAccessException.class, ex -> {
					assertThat(ex.kind()).isEqualTo(Kind.CIRCUIT_OPEN);
					assertThat(ex.retryable()).isFalse();
				});
		assertThat(client.breakerState(CATALOG.key())).isEqualTo(CircuitBreaker.State.OPEN);

		// con el circuito abierto no se hace ninguna petición más
		assertThatThrownBy(() -> client.fetch(catalog(Pagination.offset(2)), 0, 0))
				.isInstanceOfSatisfying(SourceAccessException.class,
						ex -> assertThat(ex.kind()).isEqualTo(Kind.CIRCUIT_OPEN));
		server.verify();
		// otro dataset no se ve afectado
		assertThat(client.breakerState("sede:distrito")).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void buildsUrlsWithExtensionQueryRowsAndStart() {
		assertThat(ZaragozaHttpClient.buildUri(catalog(Pagination.offset(500)), 1000))
				.hasToString(CATALOG_URL + "?fl=id,title,formato&rows=500&start=1000");
		var ocds = new SourceDescriptor(DatasetRef.of(Sources.OCDS, "contracting-process"),
				URI.create("https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds/contracting-process.json"),
				Map.of(), Pagination.none(20000), ResponseShape.ARRAY);
		assertThat(ZaragozaHttpClient.buildUri(ocds, 0))
				.hasToString("https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds/contracting-process.json?rows=20000");
		var geo = new SourceDescriptor(DatasetRef.of(Sources.SEDE, "distrito"),
				URI.create("https://www.zaragoza.es/sede/servicio/distrito.json"), Map.of("srsname", "wgs84"),
				Pagination.none(500), ResponseShape.ENVELOPE);
		assertThat(ZaragozaHttpClient.buildUri(geo, 0))
				.hasToString("https://www.zaragoza.es/sede/servicio/distrito.json?srsname=wgs84&rows=500");
	}

	@Test
	void sendsNoConditionalHeadersByDefault() {
		server.expect(requestTo(startsWith(CATALOG_URL)))
				.andExpect(request -> assertThat(request.getHeaders().containsHeader(HttpHeaders.IF_MODIFIED_SINCE))
						.as("If-Modified-Since nunca se honra (S0.5); no se envía").isFalse())
				.andExpect(request -> assertThat(request.getHeaders().containsHeader(HttpHeaders.IF_NONE_MATCH))
						.isFalse())
				.andRespond(withSuccess("{\"totalCount\":0,\"result\":[]}", JSON_UTF8));

		RawPage page = client.fetch(catalog(Pagination.offset(2)), 0, 0);

		assertThat(page.recordCount()).isZero();
		assertThat(page.totalCount()).isZero();
	}

	static SourceDescriptor catalog(Pagination pagination) {
		return new SourceDescriptor(CATALOG, URI.create(CATALOG_URL), Map.of("fl", "id,title,formato"), pagination,
				ResponseShape.ENVELOPE);
	}

}
