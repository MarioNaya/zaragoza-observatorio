package es.zaragoza.observatory.shared;

import java.time.Instant;

/**
 * Ventana temporal de una consulta de cruce (ADR-019 §5). Los dos extremos son opcionales: {@code null} no
 * filtra por ese lado.
 * <p>
 * <b>La ventana no dice sobre qué fecha se aplica</b>, y eso es deliberado: cada medida la aplica sobre la suya
 * y la declara. La misma ventana de 2024 son, en {@code citizen}, las quejas dadas de alta en 2024, y en
 * {@code urban}, los locales dados de alta en 2024 —no las licencias de 2024—. Fundir las dos cosas en un
 * «filtro por fecha» sin nombre sería dar por comparable lo que no lo es (regla 6).
 *
 * @param from desde, inclusive; {@code null} sin límite
 * @param to hasta, exclusive; {@code null} sin límite
 */
public record DateWindow(Instant from, Instant to) {

	public DateWindow {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new IllegalArgumentException("from must be before to");
		}
	}

	/** Sin límites: toda la serie ingerida. */
	public static DateWindow open() {
		return new DateWindow(null, null);
	}

	public boolean isOpen() {
		return from == null && to == null;
	}

}
