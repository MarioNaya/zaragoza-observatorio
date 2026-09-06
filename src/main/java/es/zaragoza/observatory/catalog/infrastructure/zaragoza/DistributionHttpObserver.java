package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.Dataset.Distribution;
import es.zaragoza.observatory.catalog.domain.DistributionObserver;
import es.zaragoza.observatory.catalog.domain.Observation;
import es.zaragoza.observatory.catalog.domain.ObservationMethod;
import es.zaragoza.observatory.shared.HttpDates;
import es.zaragoza.observatory.shared.ZaragozaTime;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de observación sobre la API municipal (S1.1, recomendaciones 1–4). Prueba en orden los endpoints de
 * la API ({@code rows=1} y {@code sort=<campo> desc}), los ficheros ({@code HEAD}) y el WFS
 * ({@code resultType=hits}) y devuelve la primera observación con medida; si ninguna la tiene, el primer intento
 * fallido con su causa. Sin reintentos ni circuit breaker (la ficha se vuelve a observar al día siguiente);
 * nunca sigue redirecciones (lo decide {@code spring.http.clients.redirects}); una pausa entre peticiones por
 * cortesía (S0.5). Nunca lanza por un fallo de la fuente.
 */
public class DistributionHttpObserver implements DistributionObserver {

	private static final Logger log = LoggerFactory.getLogger(DistributionHttpObserver.class);
	private static final int MAX_API_CANDIDATES = 2;
	private static final int MAX_WFS_CANDIDATES = 2;
	private static final int SNIPPET = 120;
	private static final Pattern NUMBER_MATCHED = Pattern.compile("numberMatched=\"(\\d+)\"");

	private final RestClient rest;
	private final JsonMapper json;
	private final Clock clock;
	private final Duration requestDelay;
	private final int maxFileDistributions;
	private long lastRequestNanos;
	private boolean requested;

	public DistributionHttpObserver(RestClient rest, JsonMapper json, Clock clock, Duration requestDelay,
			int maxFileDistributions) {
		this.rest = Objects.requireNonNull(rest);
		this.json = Objects.requireNonNull(json);
		this.clock = Objects.requireNonNull(clock);
		this.requestDelay = Objects.requireNonNull(requestDelay);
		if (maxFileDistributions < 1) {
			throw new IllegalArgumentException("maxFileDistributions must be positive");
		}
		this.maxFileDistributions = maxFileDistributions;
	}

	@Override
	public synchronized Observation observe(Dataset dataset) {
		Instant at = clock.instant();
		List<Distribution> apis = ObservationUrls.apiCandidates(dataset);
		List<Distribution> files = ObservationUrls.fileCandidates(dataset);
		List<Distribution> layers = ObservationUrls.wfsCandidates(dataset);
		if (apis.isEmpty() && files.isEmpty() && layers.isEmpty()) {
			return Observation.notObservable(at);
		}
		Observation firstFailure = null;
		for (Distribution d : head(apis, MAX_API_CANDIDATES)) {
			Observation o = observeApi(dataset, d, at);
			if (o.measured()) {
				return o;
			}
			firstFailure = firstFailure == null ? o : firstFailure;
		}
		if (!files.isEmpty()) {
			Observation o = observeFiles(files, at);
			if (o.measured()) {
				return o;
			}
			firstFailure = firstFailure == null ? o : firstFailure;
		}
		for (Distribution d : head(layers, MAX_WFS_CANDIDATES)) {
			Observation o = observeWfs(d, at);
			if (o.measured()) {
				return o;
			}
			firstFailure = firstFailure == null ? o : firstFailure;
		}
		return firstFailure;
	}

	// --- API de la sede ---------------------------------------------------------------------------------------

	Observation observeApi(Dataset dataset, Distribution distribution, Instant at) {
		URI url;
		try {
			url = ObservationUrls.apiUrl(distribution, Boolean.TRUE.equals(dataset.hasGeo()));
		}
		catch (IllegalArgumentException ex) {
			return Observation.failed(ObservationMethod.API_COUNT, ObservationUrls.url(distribution), at,
					"URL no válida: " + ex.getMessage());
		}
		Http r = exchange(HttpMethod.GET, url);
		if (r.error() != null) {
			return Observation.failed(ObservationMethod.API_COUNT, url.toString(), at, r.error());
		}
		if (r.status() != 200) {
			return Observation.failed(ObservationMethod.API_COUNT, url.toString(), at, r.describe());
		}
		JsonNode root = parse(r.body());
		if (root == null) {
			return Observation.failed(ObservationMethod.API_COUNT, url.toString(), at,
					"respuesta no JSON (" + r.contentType() + ")");
		}
		Listing listing = Listing.of(root);
		if (listing == null) {
			return Observation.failed(ObservationMethod.API_COUNT, url.toString(), at,
					"sin lista de registros: claves " + root.propertyNames());
		}
		String field = dateField(listing.first());
		if (field == null) {
			if (listing.total() != null) {
				return Observation.measured(ObservationMethod.API_COUNT, url.toString(), at, null, listing.total(),
						"sin campo de fecha admitido en el registro");
			}
			return Observation.failed(ObservationMethod.API_COUNT, url.toString(), at,
					"sin totalCount ni campo de fecha admitido en el registro");
		}
		URI sorted = ObservationUrls.sortedUrl(url, field);
		Http s = exchange(HttpMethod.GET, sorted);
		JsonNode sortedRoot = s.error() == null && s.status() == 200 ? parse(s.body()) : null;
		Listing sortedListing = sortedRoot == null ? null : Listing.of(sortedRoot);
		Instant lastChange = sortedListing == null ? null
				: ZaragozaTime.parseInstant(text(sortedListing.first().path(field)));
		if (lastChange != null) {
			Integer records = sortedListing.total() != null ? sortedListing.total() : listing.total();
			return Observation.measured(ObservationMethod.API_MAX_DATE, sorted.toString(), at, lastChange, records,
					field);
		}
		String why = s.error() != null ? s.error()
				: s.status() != 200 ? "HTTP " + s.status()
						: sortedRoot == null ? "respuesta no JSON (" + s.contentType() + ")"
								: "sin valor de " + field + " en el primer registro";
		if (listing.total() != null) {
			return Observation.measured(ObservationMethod.API_COUNT, url.toString(), at, null, listing.total(),
					"sort=" + field + " desc no utilizable: " + why);
		}
		return Observation.failed(ObservationMethod.API_MAX_DATE, sorted.toString(), at,
				"sort=" + field + " desc no utilizable (" + why + ") y sin totalCount");
	}

	/** Primer campo de la lista blanca presente en el registro con un valor de fecha reconocible. */
	static String dateField(JsonNode record) {
		if (record == null || !record.isObject()) {
			return null;
		}
		for (String field : ObservationUrls.DATE_FIELDS) {
			JsonNode value = record.get(field);
			if (value != null && value.isValueNode() && ZaragozaTime.parseInstant(text(value)) != null) {
				return field;
			}
		}
		return null;
	}

	/** Formas de respuesta observadas (S0.5, S1.1): array, envoltorio {@code result}, {@code records}, GeoJSON. */
	record Listing(JsonNode items, Integer total, boolean geoJson) {

		static Listing of(JsonNode root) {
			if (root.isArray()) {
				return new Listing(root, null, false);
			}
			if (!root.isObject()) {
				return null;
			}
			if (root.path("result").isArray() || root.path("totalCount").isNumber()) {
				return new Listing(root.path("result"), intOrNull(root.path("totalCount")), false);
			}
			if (root.path("records").isArray()) {
				return new Listing(root.path("records"), intOrNull(root.path("totalRecords")), false);
			}
			if (root.path("features").isArray()) {
				return new Listing(root.path("features"), intOrNull(root.path("totalCount")), true);
			}
			return null;
		}

		JsonNode first() {
			JsonNode first = items.path(0);
			return geoJson ? first.path("properties") : first;
		}

		private static Integer intOrNull(JsonNode node) {
			return node.isNumber() ? node.asInt() : null;
		}
	}

	// --- ficheros -----------------------------------------------------------------------------------------------

	Observation observeFiles(List<Distribution> files, Instant at) {
		Instant max = null;
		String maxUrl = null;
		String firstUrl = null;
		String firstError = null;
		int consulted = 0;
		int withLastModified = 0;
		for (Distribution d : head(files, maxFileDistributions)) {
			String raw = ObservationUrls.url(d);
			if (firstUrl == null) {
				firstUrl = raw;
			}
			URI url;
			try {
				url = ObservationUrls.fileUrl(d);
			}
			catch (IllegalArgumentException ex) {
				firstError = firstError == null ? "URL no válida: " + raw : firstError;
				continue;
			}
			consulted++;
			Http r = exchange(HttpMethod.HEAD, url);
			if (r.error() != null) {
				firstError = firstError == null ? r.error() : firstError;
				continue;
			}
			if (r.status() != 200 && r.status() != 206) {
				firstError = firstError == null ? r.describe() : firstError;
				continue;
			}
			Instant lastModified = HttpDates.lastModified(r.headers().getFirst(HttpHeaders.LAST_MODIFIED));
			if (lastModified == null) {
				continue;
			}
			withLastModified++;
			if (max == null || lastModified.isAfter(max)) {
				max = lastModified;
				maxUrl = url.toString();
			}
		}
		if (max != null) {
			return Observation.measured(ObservationMethod.FILE_HEADERS, maxUrl, at, max, null,
					consulted + " fichero(s) consultado(s), " + withLastModified + " con Last-Modified");
		}
		return Observation.failed(ObservationMethod.FILE_HEADERS, firstUrl, at,
				firstError != null ? firstError : "sin Last-Modified en " + consulted + " fichero(s)");
	}

	// --- WFS ----------------------------------------------------------------------------------------------------

	Observation observeWfs(Distribution layer, Instant at) {
		URI url;
		try {
			url = ObservationUrls.wfsHitsUrl(layer);
		}
		catch (IllegalArgumentException ex) {
			return Observation.failed(ObservationMethod.WFS_HITS, ObservationUrls.url(layer), at,
					"URL no válida: " + ex.getMessage());
		}
		Http r = exchange(HttpMethod.GET, url);
		if (r.error() != null) {
			return Observation.failed(ObservationMethod.WFS_HITS, url.toString(), at, r.error());
		}
		if (r.status() != 200) {
			return Observation.failed(ObservationMethod.WFS_HITS, url.toString(), at, r.describe());
		}
		Matcher m = NUMBER_MATCHED.matcher(r.body());
		if (!m.find()) {
			return Observation.failed(ObservationMethod.WFS_HITS, url.toString(), at,
					"sin numberMatched en la respuesta (" + r.contentType() + ")");
		}
		return Observation.measured(ObservationMethod.WFS_HITS, url.toString(), at, null,
				Integer.parseInt(m.group(1)), "typeNames=" + layer.wfsFeatureName());
	}

	// --- HTTP -----------------------------------------------------------------------------------------------------

	record Http(int status, HttpHeaders headers, String body, String error) {

		String contentType() {
			MediaType type = headers.getContentType();
			return type == null ? "sin Content-Type" : type.toString();
		}

		String describe() {
			String snippet = body.strip().replaceAll("\\s+", " ");
			if (snippet.length() > SNIPPET) {
				snippet = snippet.substring(0, SNIPPET) + "…";
			}
			String location = status >= 300 && status < 400 && headers.getLocation() != null
					? " -> " + headers.getLocation() : "";
			return "HTTP " + status + " " + contentType() + location + (snippet.isEmpty() ? "" : ": " + snippet);
		}
	}

	private Http exchange(HttpMethod method, URI url) {
		throttle();
		try {
			return rest.method(method).uri(url).exchange((request, response) -> {
				String body = method == HttpMethod.HEAD ? ""
						: new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
				return new Http(response.getStatusCode().value(), response.getHeaders(), body, null);
			});
		}
		catch (ResourceAccessException ex) {
			return new Http(-1, new HttpHeaders(), "", "E/S: " + rootMessage(ex));
		}
		catch (RuntimeException ex) {
			log.debug("{} {} failed", method, url, ex);
			return new Http(-1, new HttpHeaders(), "", ex.getClass().getSimpleName() + ": " + rootMessage(ex));
		}
	}

	private void throttle() {
		if (requested && !requestDelay.isZero()) {
			long waitMillis = requestDelay.toMillis() - (System.nanoTime() - lastRequestNanos) / 1_000_000;
			if (waitMillis > 0) {
				try {
					Thread.sleep(waitMillis);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}
		requested = true;
		lastRequestNanos = System.nanoTime();
	}

	/** {@code null} si el cuerpo no es JSON (cuerpo vacío o HTML con {@code Content-Type} JSON: ambos ocurren, S1.1). */
	private JsonNode parse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonNode node = json.readTree(body);
			return node == null || node.isMissingNode() ? null : node;
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return "";
		}
		return node.isString() ? node.stringValue() : node.asString("");
	}

	private static <T> List<T> head(List<T> list, int max) {
		return list.size() <= max ? list : list.subList(0, max);
	}

	private static String rootMessage(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}

}
