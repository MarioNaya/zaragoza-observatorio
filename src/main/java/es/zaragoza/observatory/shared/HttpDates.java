package es.zaragoza.observatory.shared;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Cabeceras de fecha HTTP de la infraestructura municipal. Conviven dos formatos (S0.5, S1.1): el RFC 1123
 * estándar con {@code GMT} en el contenido estático de Apache y en GeoServer ({@code Wed, 13 Feb 2013 09:30:24 GMT})
 * y el de la sede con zona nombrada ({@code Tue, 20 Jan 2026 13:12:38 CET}). Devuelve {@code null} si no hay
 * cabecera o no se entiende: es un dato informativo, nunca bloqueante.
 */
public final class HttpDates {

	/** Patrón de la sede (S0.5, «Cabeceras condicionales»). */
	static final DateTimeFormatter ZONE_NAME = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz",
			Locale.ENGLISH);

	private HttpDates() {
	}

	public static Instant lastModified(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		String value = header.strip();
		try {
			return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException ignored) {
			// segundo intento con zona nombrada
		}
		try {
			return ZonedDateTime.parse(value, ZONE_NAME).toInstant();
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

}
