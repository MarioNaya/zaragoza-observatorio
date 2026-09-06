package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Una operación del Swagger 2.0 de la API municipal ({@code sede/servicio/catalogo/api.json}, S0.1 y S1.2):
 * inventario de endpoints que se cruza con las fichas del catálogo por {@link Dataset#apiTag()}. Todos los campos
 * existen en el documento; ninguno se inventa. El documento no trae {@code operationId}, {@code description} ni
 * {@code deprecated}, y no tiene lista de {@code tags} en la raíz: los tags son los que declara cada operación.
 *
 * @param tag único tag de la operación ({@code paths.<path>.<method>.tags[0]}; ninguna operación tiene más de uno)
 * @param method método HTTP en minúsculas ({@code get} en 496 de 497)
 * @param path path tal como lo documenta el Swagger, con barra inicial (11 paths llegan sin ella)
 * @param url URL absoluta: {@code schemes[0]://host + basePath + path}
 * @param summary {@code summary}, o {@code null} si está vacío (73 de 497)
 * @param ordinal posición de la operación en el documento, desde 0, para listar en el orden de la fuente
 * @param firstSeenAt primera ingesta en la que apareció
 * @param lastSeenAt última ingesta en la que apareció
 */
public record ApiEndpoint(String tag, String method, String path, String url, String summary, int ordinal,
		Instant firstSeenAt, Instant lastSeenAt) {

	public ApiEndpoint {
		if (tag == null || tag.isBlank()) {
			throw new IllegalArgumentException("tag must not be blank (path " + path + ")");
		}
		if (method == null || method.isBlank()) {
			throw new IllegalArgumentException("method must not be blank (path " + path + ")");
		}
		if (path == null || !path.startsWith("/")) {
			throw new IllegalArgumentException("path must start with '/': " + path);
		}
		Objects.requireNonNull(url, "url must not be null");
		if (ordinal < 0) {
			throw new IllegalArgumentException("ordinal must not be negative");
		}
		summary = summary == null || summary.isBlank() ? null : summary.strip();
	}

	/** Clave natural dentro del documento. */
	public String key() {
		return tag + " " + method + " " + path;
	}

	/** Un path con parámetros ({@code /servicio/distrito/{id}}) no es un listado consultable tal cual. */
	public boolean templated() {
		return path.contains("{");
	}

	/** Copia con las marcas de ingesta actualizadas. */
	public ApiEndpoint seen(Instant firstSeenAt, Instant lastSeenAt) {
		return new ApiEndpoint(tag, method, path, url, summary, ordinal, Objects.requireNonNull(firstSeenAt),
				Objects.requireNonNull(lastSeenAt));
	}

}
