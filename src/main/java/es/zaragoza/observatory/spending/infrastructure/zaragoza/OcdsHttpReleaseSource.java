package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import es.zaragoza.observatory.spending.domain.ReleaseContent;
import es.zaragoza.observatory.spending.domain.ReleaseRead;
import es.zaragoza.observatory.spending.domain.ReleaseSource;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Lee de OCDS lo que no cabe en el ciclo de una página: el detalle de cada proceso y el listado documentado. Usa
 * el cliente HTTP común de la aplicación (regla 23) con una pausa entre peticiones por cortesía (S0.5), y sin
 * reintentos ni circuit breaker: lo que falle hoy se reintenta cuando toque, como en el muestreo observado de
 * {@code catalog} (ADR-005).
 * <p>
 * Traduce los tres desenlaces de la fuente a los tres estados del dominio, y el 404 con cuidado: <b>llega con un
 * cuerpo que dice {@code {"status":400,…}}</b> y con un mensaje de error interno filtrado al exterior. Manda la
 * cabecera. Un 404 significa que el release aún no está publicado, no que el proceso no exista: son 2.379 de
 * 8.001 y son los expedientes recientes.
 */
public class OcdsHttpReleaseSource implements ReleaseSource {

	private static final Logger log = LoggerFactory.getLogger(OcdsHttpReleaseSource.class);

	private final RestClient rest;
	private final OcdsReleaseJsonTranslator releaseTranslator;
	private final OcdsListJsonTranslator listTranslator;
	private final SpendingProperties properties;
	private final Duration requestDelay;
	private long lastRequestNanos;
	private boolean requested;

	public OcdsHttpReleaseSource(RestClient rest, OcdsReleaseJsonTranslator releaseTranslator,
			OcdsListJsonTranslator listTranslator, SpendingProperties properties) {
		this.rest = Objects.requireNonNull(rest);
		this.releaseTranslator = Objects.requireNonNull(releaseTranslator);
		this.listTranslator = Objects.requireNonNull(listTranslator);
		this.properties = Objects.requireNonNull(properties);
		this.requestDelay = properties.releases().requestDelay();
	}

	@Override
	public ReleaseRead read(String ocid) {
		URI uri = properties.detailUrl(ocid);
		if (uri == null) {
			log.warn("spending: ocid con forma inesperada, no se pide su detalle: {}", ocid);
			return ReleaseRead.unreadable(ocid);
		}
		String body;
		try {
			pause();
			body = rest.get().uri(uri).retrieve().body(String.class);
		}
		catch (RestClientResponseException ex) {
			HttpStatusCode status = ex.getStatusCode();
			if (status.value() == 404) {
				// El release no está publicado todavía. Es un hecho de la fuente, no un error de ingesta.
				return ReleaseRead.absent(ocid);
			}
			log.warn("spending: {} respondió {} ({})", ocid, status.value(), uri);
			return ReleaseRead.unreadable(ocid);
		}
		catch (RuntimeException ex) {
			log.warn("spending: {} no se pudo leer ({}): {}", ocid, uri, ex.toString());
			return ReleaseRead.unreadable(ocid);
		}
		if (body == null || body.isBlank()) {
			log.warn("spending: {} devolvió un cuerpo vacío ({})", ocid, uri);
			return ReleaseRead.unreadable(ocid);
		}
		try {
			ReleaseContent content = releaseTranslator.translate(ocid, body);
			// Un 200 no garantiza release: 16 packages responden 200 con `releases` vacío (S3.1 §3).
			return content == null ? ReleaseRead.empty(ocid) : ReleaseRead.published(ocid, content);
		}
		catch (RuntimeException ex) {
			log.warn("spending: el package de {} no se pudo traducir ({}): {}", ocid, uri, ex.toString());
			return ReleaseRead.unreadable(ocid);
		}
	}

	@Override
	public Optional<List<String>> documentedOcids() {
		// El listado **sin** el interruptor `after`: justo el que documenta la API y el que esconde 2.271
		// procesos. Se pide para saber cuáles, y para comprobar que sigue siendo subconjunto del ampliado.
		URI uri = UriComponentsBuilder.fromUri(properties.listUrl()).queryParam("rows", properties.rows()).build()
				.toUri();
		try {
			pause();
			String body = rest.get().uri(uri).retrieve().body(String.class);
			if (body == null || body.isBlank()) {
				log.warn("spending: el listado documentado devolvió un cuerpo vacío ({})", uri);
				return Optional.empty();
			}
			return Optional.of(listTranslator.translate(body));
		}
		catch (RuntimeException ex) {
			log.warn("spending: el listado documentado no se pudo leer ({}): {}", uri, ex.toString());
			return Optional.empty();
		}
	}

	private void pause() {
		if (requested && !requestDelay.isZero()) {
			long remaining = requestDelay.toNanos() - (System.nanoTime() - lastRequestNanos);
			if (remaining > 0) {
				try {
					Thread.sleep(Duration.ofNanos(remaining));
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		requested = true;
		lastRequestNanos = System.nanoTime();
	}

}
