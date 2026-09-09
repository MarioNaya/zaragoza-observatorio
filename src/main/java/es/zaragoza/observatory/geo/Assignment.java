package es.zaragoza.observatory.geo;

/**
 * Cómo ha quedado la asignación territorial de un registro (ADR-011 §6 y §7). Son <b>cuatro</b> estados y
 * ninguno se puede colapsar en otro sin perder información publicable:
 * <ul>
 * <li>{@code RESOLVED}: el punto cae en una junta;</li>
 * <li>{@code AMBIGUOUS}: cae en varias porque los polígonos publicados se solapan (entorno de Juslibol, S2.1);
 * se toma la de menor id y se marca;</li>
 * <li>{@code OUTSIDE}: tiene punto y no cae en ninguna junta;</li>
 * <li>{@code NO_POINT}: no trae punto, y <b>no se rellena geocodificando la dirección</b> (ADR-011 §2). Cuánto
 * pesa depende de la fuente: del 55 % al 84 % de las quejas según el año (S2.2) y el 10,6 % de los locales con
 * licencia (S2.4).</li>
 * </ul>
 * Toda agregación territorial publica cuántos registros hay en cada uno (regla 7).
 * <p>
 * Vive en {@code geo} y no en el módulo que ingiere porque es el vocabulario del territorio, no el de una
 * fuente: los tres primeros estados son los de {@link DistrictLocation}, que {@code geo} devuelve, y el cuarto
 * es el caso que ni siquiera llega a preguntarse. Cuando la segunda fuente territorial ({@code urban}, ADR-016)
 * necesitó los mismos cuatro estados, la alternativa era copiarlos.
 */
public enum Assignment {

	RESOLVED, AMBIGUOUS, OUTSIDE, NO_POINT;

	/** Si el registro tiene junta asignada (los dos primeros). */
	public boolean hasDistrict() {
		return this == RESOLVED || this == AMBIGUOUS;
	}

	/** Traduce el resultado de una resolución geométrica; el cuarto estado no sale de aquí. */
	public static Assignment of(DistrictLocation location) {
		return switch (location.status()) {
			case RESOLVED -> RESOLVED;
			case AMBIGUOUS -> AMBIGUOUS;
			case OUTSIDE -> OUTSIDE;
		};
	}

}
