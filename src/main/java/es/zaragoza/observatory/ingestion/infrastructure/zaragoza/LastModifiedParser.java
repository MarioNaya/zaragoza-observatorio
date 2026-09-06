package es.zaragoza.observatory.ingestion.infrastructure.zaragoza;

import java.time.Instant;

import es.zaragoza.observatory.shared.HttpDates;

/**
 * Parsea la cabecera {@code Last-Modified} de la API municipal, que llega con zona {@code CET}/{@code CEST}
 * ({@code Tue, 20 Jan 2026 13:12:38 CET}) en lugar del {@code GMT} de RFC 1123 (S0.5), o en RFC 1123 en el
 * contenido estático (S1.1). La lógica vive en {@link HttpDates} (kernel compartido) porque {@code catalog} la
 * necesita para observar ficheros.
 */
final class LastModifiedParser {

	private LastModifiedParser() {
	}

	static Instant parse(String header) {
		return HttpDates.lastModified(header);
	}

}
