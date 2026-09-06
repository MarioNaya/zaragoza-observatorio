package es.zaragoza.observatory.ingestion.infrastructure.zaragoza;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Parsea la cabecera {@code Last-Modified} de la API municipal, que llega con zona {@code CET}/{@code CEST}
 * ({@code Tue, 20 Jan 2026 13:12:38 CET}) en lugar del {@code GMT} de RFC 1123 (S0.5). Se acepta también el
 * formato RFC 1123 por si algún endpoint lo emite. Devuelve {@code null} si no hay cabecera o no se entiende:
 * es un dato informativo de frescura, nunca bloqueante.
 */
final class LastModifiedParser {

	/** Patrón recomendado en S0.5 (docs/spikes/S0.5-api.md, «Cabeceras condicionales»). */
	static final DateTimeFormatter ZONE_NAME = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz",
			Locale.ENGLISH);

	private LastModifiedParser() {
	}

	static Instant parse(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		String value = header.strip();
		try {
			return ZonedDateTime.parse(value, ZONE_NAME).toInstant();
		}
		catch (DateTimeParseException ignored) {
			// segundo intento más abajo
		}
		try {
			return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

}
