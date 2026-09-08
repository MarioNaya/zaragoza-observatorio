package es.zaragoza.observatory.geo.domain;

import java.util.Locale;

/**
 * Las dos clases de junta que publica la API: 15 municipales y 14 vecinales (S0.4). No es una interpretación:
 * el título de origen lo dice literalmente («Junta Municipal Delicias», «Junta Vecinal Casetas»).
 * <p>
 * Un título que no empiece por ninguno de los dos prefijos hace fallar la traducción a propósito: si el
 * ayuntamiento cambia la nomenclatura, se ve en la ingesta y se decide, no se clasifica a ojo (regla 1).
 */
public enum DistrictKind {

	MUNICIPAL("Junta Municipal"), VECINAL("Junta Vecinal");

	private final String prefix;

	DistrictKind(String prefix) {
		this.prefix = prefix;
	}

	public String prefix() {
		return prefix;
	}

	public static DistrictKind fromTitle(String title) {
		String value = title == null ? "" : title.strip();
		for (DistrictKind kind : values()) {
			if (value.regionMatches(true, 0, kind.prefix, 0, kind.prefix.length())) {
				return kind;
			}
		}
		throw new IllegalArgumentException(
				"district title does not start with a known prefix (Junta Municipal / Junta Vecinal): " + title);
	}

	/** Quita el prefijo del título si está; devuelve el título tal cual si no. */
	public String stripPrefix(String title) {
		String value = title == null ? "" : title.strip();
		if (value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
			return value.substring(prefix.length()).strip();
		}
		return value;
	}

}
