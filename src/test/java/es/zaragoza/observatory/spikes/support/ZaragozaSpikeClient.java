package es.zaragoza.observatory.spikes.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente HTTP mínimo para spikes (ADR-002). Sin retry ni circuit breaker a propósito: queremos observar el
 * comportamiento real de la API municipal. No sigue redirecciones para poder registrarlas.
 * <p>
 * No es código de producción: el cliente real vivirá en el módulo {@code ingestion}.
 */
public final class ZaragozaSpikeClient {

	/** API REST v2 clásica ("sede"). Formato por extensión (.json, .geojson, .csv) o cabecera Accept. */
	public static final String SEDE = "https://www.zaragoza.es/sede/servicio";

	/** Catálogo de datasets (portal "espacio de datos"). Verificado el 2026-09-05. */
	public static final String DATA_SPACE = "https://www.zaragoza.es/web/espacio-de-datos/servicio";

	/** Open311. GET público; la firma HMAC solo aplica a POST. Verificado el 2026-09-05. */
	public static final String OPEN311 = "https://www.zaragoza.es/api/recurso/open311";

	private static final JsonMapper JSON = JsonMapper.shared();

	private final RestClient rest;

	public ZaragozaSpikeClient() {
		this(Duration.ofSeconds(180));
	}

	public ZaragozaSpikeClient(Duration readTimeout) {
		var httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(30))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		var factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		this.rest = RestClient.builder()
				.requestFactory(factory)
				.defaultHeader(HttpHeaders.USER_AGENT, "observatorio-zaragoza-spike/0.0.1 (+spike; contacto en repo)")
				.build();
	}

	public Response get(String absoluteUrl) {
		return get(URI.create(absoluteUrl), headers -> {
		});
	}

	public Response get(String absoluteUrl, Consumer<HttpHeaders> headers) {
		return get(URI.create(absoluteUrl), headers);
	}

	public Response get(URI uri, Consumer<HttpHeaders> headers) {
		long start = System.nanoTime();
		return rest.get().uri(uri).headers(headers).exchange((request, response) -> {
			byte[] bytes = response.getBody().readAllBytes();
			var body = new String(bytes, StandardCharsets.UTF_8);
			return new Response(uri, response.getStatusCode().value(), response.getHeaders(), body,
					Duration.ofNanos(System.nanoTime() - start));
		});
	}

	/** Petición HEAD: solo cabeceras (para ficheros descargables, S1.1). */
	public Response head(String absoluteUrl) {
		URI uri = URI.create(absoluteUrl);
		long start = System.nanoTime();
		return rest.head().uri(uri).exchange((request, response) -> {
			byte[] bytes = response.getBody().readAllBytes();
			return new Response(uri, response.getStatusCode().value(), response.getHeaders(),
					new String(bytes, StandardCharsets.UTF_8), Duration.ofNanos(System.nanoTime() - start));
		});
	}

	/**
	 * Variante que nunca lanza: los fallos de red, timeouts y URL mal formadas se devuelven como
	 * {@link Response} con estado -1 y el mensaje en el cuerpo. Para recorrer muestras grandes sin abortar.
	 */
	public Response tryGet(String absoluteUrl, Consumer<HttpHeaders> headers) {
		try {
			return get(URI.create(absoluteUrl), headers);
		}
		catch (RuntimeException e) {
			return Response.failed(absoluteUrl, e);
		}
	}

	public Response tryGet(String absoluteUrl) {
		return tryGet(absoluteUrl, h -> {
		});
	}

	public Response tryHead(String absoluteUrl) {
		try {
			return head(absoluteUrl);
		}
		catch (RuntimeException e) {
			return Response.failed(absoluteUrl, e);
		}
	}

	/** Construye una URL con query string codificada. Los valores se codifican con URLEncoder (UTF-8). */
	public static String url(String base, Map<String, String> params) {
		if (params.isEmpty()) {
			return base;
		}
		return base + "?" + params.entrySet().stream()
				.map(e -> e.getKey() + "=" + enc(e.getValue()))
				.collect(Collectors.joining("&"));
	}

	public static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public record Response(URI uri, int status, HttpHeaders headers, String body, Duration elapsed) {

		public JsonNode json() {
			return JSON.readTree(body);
		}

		public boolean isJson() {
			MediaType type = headers.getContentType();
			return type != null && type.getSubtype().toLowerCase().contains("json");
		}

		public String header(String name) {
			return headers.getFirst(name);
		}

		public String contentType() {
			MediaType type = headers.getContentType();
			return type == null ? "-" : type.toString();
		}

		/** Una línea con lo esencial de la petición, pensada para el informe del spike. */
		public String summary() {
			return "GET " + uri + " -> " + status + " " + contentType() + " bytes=" + body.length() + " ms="
					+ elapsed.toMillis() + " etag=" + header("ETag") + " lastModified=" + header("Last-Modified")
					+ " location=" + header("Location");
		}

		public static Response fromIoException(URI uri, IOException e) {
			return new Response(uri, -1, new HttpHeaders(), e.toString(), Duration.ZERO);
		}

		public static Response failed(String url, Throwable e) {
			URI uri;
			try {
				uri = URI.create(url);
			}
			catch (RuntimeException invalid) {
				uri = URI.create("invalid:url");
			}
			Throwable root = e;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
			}
			return new Response(uri, -1, new HttpHeaders(), root.getClass().getSimpleName() + ": " + root.getMessage(),
					Duration.ZERO);
		}

		public boolean failed() {
			return status < 0;
		}

		public long contentLength() {
			return headers.getContentLength();
		}
	}

}
