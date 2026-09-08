package es.zaragoza.observatory.citizen.domain;

import java.util.Locale;

/**
 * Estado publicado por el origen. Solo se han observado {@code open} y {@code closed} (S0.3, S2.2: 6.674
 * cerradas y 326 abiertas en 7.000 registros), pero un valor nuevo no puede tirar la ingesta ni desaparecer sin
 * dejar rastro: se guarda como {@link #UNKNOWN} y se cuenta como tal.
 */
public enum ServiceRequestStatus {

	OPEN, CLOSED, UNKNOWN;

	/** Traduce el valor del origen; cualquier cosa distinta de {@code open}/{@code closed} es {@code UNKNOWN}. */
	public static ServiceRequestStatus of(String raw) {
		if (raw == null) {
			return UNKNOWN;
		}
		return switch (raw.strip().toLowerCase(Locale.ROOT)) {
			case "open" -> OPEN;
			case "closed" -> CLOSED;
			default -> UNKNOWN;
		};
	}

}
