package es.zaragoza.observatory.shared;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Las fechas sin zona de la API municipal son hora local de Zaragoza (S0.5, recomendación 4). */
public final class ZaragozaTime {

	public static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

	private static final DateTimeFormatter SLASHED = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private ZaragozaTime() {
	}

	/**
	 * Convierte a instante los formatos de fecha observados en los registros de la API municipal (S0.5, S1.1):
	 * {@code 2026-06-12T10:57:57} (sin zona: hora local), {@code 2026-09-04T12:07:57Z} o con offset,
	 * {@code 2014-06-25}, {@code 20260831} ({@code yyyyMMdd}, presupuesto y hemeroteca) y {@code 25/06/2014}.
	 * Las fechas sin hora son la medianoche local. Devuelve {@code null} si no reconoce el valor.
	 */
	public static Instant parseInstant(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String s = value.strip();
		try {
			return OffsetDateTime.parse(s).toInstant();
		}
		catch (DateTimeParseException ignored) {
			// siguiente formato
		}
		try {
			return LocalDateTime.parse(s).atZone(ZONE).toInstant();
		}
		catch (DateTimeParseException ignored) {
			// siguiente formato
		}
		LocalDate date = parseDate(s);
		return date == null ? null : date.atStartOfDay(ZONE).toInstant();
	}

	private static LocalDate parseDate(String s) {
		try {
			if (s.matches("\\d{8}")) {
				return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
			}
			if (s.matches("\\d{2}/\\d{2}/\\d{4}")) {
				return LocalDate.parse(s, SLASHED);
			}
			if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
				return LocalDate.parse(s);
			}
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
		return null;
	}

}
