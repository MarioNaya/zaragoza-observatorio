package es.zaragoza.observatory.ingestion;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Una página cruda obtenida de la fuente, tal cual llegó, más los metadatos que {@code ingestion} extrae para
 * paginar y para la frescura observada. El cuerpo no se interpreta aquí: lo traduce el adaptador
 * anti-corrupción del módulo de dominio que recibe la página (SPEC.md §4.4, §4.5 paso 3).
 *
 * @param number índice de página, desde 0
 * @param start valor de {@code start} enviado (0 cuando no se pagina)
 * @param url URL completa de la petición, con query string
 * @param contentType cabecera {@code Content-Type} de la respuesta, o {@code null}
 * @param body cuerpo de la respuesta en UTF-8
 * @param recordCount registros de la página según la forma declarada ({@code result.length} o tamaño del array)
 * @param totalCount {@code totalCount} del envoltorio si la fuente lo devuelve; {@code null} en otro caso
 * @param sourceLastModified cabecera {@code Last-Modified} parseada (llega con zona {@code CET}/{@code CEST}, S0.5),
 * o {@code null}
 * @param etag cabecera {@code ETag} cruda, o {@code null} (solo Open311 la devuelve, S0.5)
 * @param fetchedAt instante de la petición
 * @param elapsed duración de la petición
 */
public record RawPage(int number, int start, URI url, String contentType, String body, int recordCount,
		Integer totalCount, Instant sourceLastModified, String etag, Instant fetchedAt, Duration elapsed) {

	public RawPage {
		Objects.requireNonNull(url, "url must not be null");
		Objects.requireNonNull(body, "body must not be null");
		Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
		Objects.requireNonNull(elapsed, "elapsed must not be null");
		if (number < 0 || start < 0 || recordCount < 0) {
			throw new IllegalArgumentException("number, start and recordCount must not be negative");
		}
	}

	public int byteSize() {
		return body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
	}

}
