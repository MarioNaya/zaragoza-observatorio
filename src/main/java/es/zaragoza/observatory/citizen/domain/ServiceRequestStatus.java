package es.zaragoza.observatory.citizen.domain;

import java.util.Locale;

/**
 * Estado publicado por el origen.
 * <p>
 * S0.3 y S2.2 solo vieron {@code open} y {@code closed} en 7.000 registros muestreados. La <b>carga completa</b>
 * de los 89.432 sacó un tercero, {@code rejected}, con <b>una sola aparición</b> (la queja 412792, de 2014). Ese
 * registro no se perdió ni tumbó la ingesta porque cualquier valor no reconocido cae en {@link #UNKNOWN} y se
 * cuenta como tal: es exactamente para lo que existe ese valor, y por eso sigue existiendo ahora que
 * {@code rejected} está reconocido.
 * <p>
 * El origen no deja contar los rechazados por su cuenta: {@code q=status==rejected} responde 400 y
 * {@code status=rejected} como parámetro se ignora en silencio y devuelve cerradas (comprobado el 2026-09-08).
 * El único recuento fiable es el propio.
 */
public enum ServiceRequestStatus {

	OPEN, CLOSED,
	/** Rechazada por el ayuntamiento. Vista por primera vez en la carga completa del 2026-09-08. */
	REJECTED,
	/** Cualquier valor que el origen publique y aquí no se conozca todavía: se marca, no se descarta. */
	UNKNOWN;

	/** Traduce el valor del origen; lo que no se reconozca es {@code UNKNOWN}. */
	public static ServiceRequestStatus of(String raw) {
		if (raw == null) {
			return UNKNOWN;
		}
		return switch (raw.strip().toLowerCase(Locale.ROOT)) {
			case "open" -> OPEN;
			case "closed" -> CLOSED;
			case "rejected" -> REJECTED;
			default -> UNKNOWN;
		};
	}

}
