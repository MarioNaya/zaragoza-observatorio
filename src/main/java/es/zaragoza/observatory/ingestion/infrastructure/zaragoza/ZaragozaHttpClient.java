package es.zaragoza.observatory.ingestion.infrastructure.zaragoza;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination.Mode;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.ingestion.domain.SourceAccessException;
import es.zaragoza.observatory.ingestion.domain.SourceAccessException.Kind;
import es.zaragoza.observatory.ingestion.domain.SourceGateway;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador hacia la API municipal (SPEC.md §4.4 «anti-corruption layer»; reglas de S0.5 y ADR-004):
 * <ul>
 * <li>construye la URL con la extensión y los parámetros del {@link SourceDescriptor} más {@code rows} y, si
 * procede, {@code start};</li>
 * <li>clasifica la respuesta ({@link SourceAccessException.Kind}): 5xx, timeouts, E/S y cuerpos no JSON son
 * reintentables; 4xx no (404 JSON «Registro no encontrado» es ausencia definitiva);</li>
 * <li>reintenta con backoff, mantiene un circuit breaker por dataset y limita la concurrencia por semáforo;</li>
 * <li>parsea {@code Last-Modified} con zona {@code CET}/{@code CEST} (no RFC 1123).</li>
 * </ul>
 * Los timeouts y el no seguir redirecciones se configuran en el {@code RestClient} que recibe.
 */
public class ZaragozaHttpClient implements SourceGateway {

	private static final Logger log = LoggerFactory.getLogger(ZaragozaHttpClient.class);
	private static final int ERROR_SNIPPET = 200;

	private final RestClient rest;
	private final JsonMapper json;
	private final Retry retry;
	private final CircuitBreakerRegistry breakers;
	private final Semaphore slots;
	private final Clock clock;

	public ZaragozaHttpClient(RestClient rest, JsonMapper json, Retry retry, CircuitBreakerRegistry breakers,
			Semaphore slots, Clock clock) {
		this.rest = Objects.requireNonNull(rest);
		this.json = Objects.requireNonNull(json);
		this.retry = Objects.requireNonNull(retry);
		this.breakers = Objects.requireNonNull(breakers);
		this.slots = Objects.requireNonNull(slots);
		this.clock = Objects.requireNonNull(clock);
	}

	/** Predicado compartido por el retry y el circuit breaker: solo cuentan los fallos reintentables. */
	public static boolean isRetryable(Throwable failure) {
		return failure instanceof SourceAccessException ex && ex.retryable();
	}

	@Override
	public RawPage fetch(SourceDescriptor source, int pageNumber, int start) {
		URI uri = buildUri(source, start);
		CircuitBreaker breaker = breakers.circuitBreaker(source.dataset().key());
		try {
			return retry.executeSupplier(() -> breaker.executeSupplier(() -> exchange(source, uri, pageNumber, start)));
		}
		catch (CallNotPermittedException ex) {
			throw new SourceAccessException(Kind.CIRCUIT_OPEN, null,
					"circuit breaker open for " + source.dataset() + " (" + breaker.getState() + ")", ex);
		}
	}

	static URI buildUri(SourceDescriptor source, int start) {
		var builder = UriComponentsBuilder.fromUri(source.url());
		source.query().forEach(builder::queryParam);
		builder.queryParam("rows", source.pagination().rows());
		if (source.pagination().mode() == Mode.OFFSET) {
			builder.queryParam("start", start);
		}
		return builder.build().toUri();
	}

	private RawPage exchange(SourceDescriptor source, URI uri, int pageNumber, int start) {
		acquireSlot();
		Instant fetchedAt = clock.instant();
		long begin = System.nanoTime();
		try {
			return rest.get().uri(uri).exchange((request, response) -> {
				int status = response.getStatusCode().value();
				HttpHeaders headers = response.getHeaders();
				String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
				Duration elapsed = Duration.ofNanos(System.nanoTime() - begin);
				return classify(source, uri, pageNumber, start, status, headers, body, fetchedAt, elapsed);
			});
		}
		catch (ResourceAccessException ex) {
			throw new SourceAccessException(isTimeout(ex) ? Kind.TIMEOUT : Kind.IO, null,
					"GET " + uri + " failed: " + ex.getMessage(), ex);
		}
		finally {
			slots.release();
		}
	}

	private void acquireSlot() {
		try {
			slots.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SourceAccessException(Kind.IO, null, "interrupted while waiting for a request slot", ex);
		}
	}

	private static boolean isTimeout(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof HttpTimeoutException || t instanceof TimeoutException
					|| t instanceof java.net.SocketTimeoutException) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private RawPage classify(SourceDescriptor source, URI uri, int pageNumber, int start, int status,
			HttpHeaders headers, String body, Instant fetchedAt, Duration elapsed) {
		MediaType contentType = headers.getContentType();
		String contentTypeText = contentType == null ? null : contentType.toString();
		if (status >= 500) {
			throw new SourceAccessException(Kind.SERVER_ERROR, status, describe(uri, status, body));
		}
		if (status == 404) {
			throw new SourceAccessException(Kind.NOT_FOUND, status, describe(uri, status, body));
		}
		if (status >= 400) {
			throw new SourceAccessException(Kind.CLIENT_ERROR, status, describe(uri, status, body));
		}
		if (status >= 300) {
			throw new SourceAccessException(Kind.MALFORMED, status,
					describe(uri, status, "redirected to " + headers.getFirst(HttpHeaders.LOCATION)));
		}
		if (!isJson(contentType)) {
			throw new SourceAccessException(Kind.MALFORMED, status,
					describe(uri, status, "expected JSON but got " + contentTypeText + ": " + body));
		}
		JsonNode root;
		try {
			root = json.readTree(body);
		}
		catch (JacksonException ex) {
			throw new SourceAccessException(Kind.MALFORMED, status, describe(uri, status, "unparseable JSON"), ex);
		}
		int recordCount;
		Integer totalCount = null;
		if (source.shape() == ResponseShape.ENVELOPE) {
			if (!root.isObject()) {
				throw new SourceAccessException(Kind.MALFORMED, status,
						describe(uri, status, "expected an envelope object but got " + root.getNodeType()));
			}
			JsonNode result = root.path("result");
			recordCount = result.isArray() ? result.size() : 0;
			JsonNode total = root.path("totalCount");
			totalCount = total.isNumber() ? total.asInt() : null;
		}
		else {
			if (!root.isArray()) {
				throw new SourceAccessException(Kind.MALFORMED, status,
						describe(uri, status, "expected a JSON array but got " + root.getNodeType()));
			}
			recordCount = root.size();
		}
		Instant lastModified = LastModifiedParser.parse(headers.getFirst(HttpHeaders.LAST_MODIFIED));
		if (log.isDebugEnabled()) {
			log.debug("GET {} -> {} {} bytes={} records={} totalCount={} ms={}", uri, status, contentTypeText,
					body.length(), recordCount, totalCount, elapsed.toMillis());
		}
		return new RawPage(pageNumber, start, uri, contentTypeText, body, recordCount, totalCount, lastModified,
				headers.getFirst(HttpHeaders.ETAG), fetchedAt, elapsed);
	}

	private static boolean isJson(MediaType contentType) {
		return contentType != null && contentType.getSubtype().toLowerCase().contains("json");
	}

	private static String describe(URI uri, int status, String body) {
		String snippet = body == null ? "" : body.strip();
		if (snippet.length() > ERROR_SNIPPET) {
			snippet = snippet.substring(0, ERROR_SNIPPET) + "…";
		}
		return "GET " + uri + " -> " + status + (snippet.isEmpty() ? "" : " " + snippet);
	}

	/** Solo para diagnósticos: estado del circuit breaker de un dataset. */
	public CircuitBreaker.State breakerState(String datasetKey) {
		return breakers.circuitBreaker(datasetKey).getState();
	}

}
