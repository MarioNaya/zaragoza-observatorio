package es.zaragoza.observatory.citizen.domain;

/**
 * Cómo ha quedado la asignación territorial de un registro (ADR-011 §6 y §7). Son <b>cuatro</b> estados y
 * ninguno se puede colapsar en otro sin perder información publicable:
 * <ul>
 * <li>{@code RESOLVED}: el punto cae en una junta;</li>
 * <li>{@code AMBIGUOUS}: cae en varias porque los polígonos publicados se solapan (entorno de Juslibol, S2.1);
 * se toma la de menor id y se marca;</li>
 * <li>{@code OUTSIDE}: tiene punto y no cae en ninguna junta;</li>
 * <li>{@code NO_POINT}: no trae punto. Es el caso mayoritario en esta fuente —del 55 % al 84 % según el año
 * (S2.2)— y <b>no se rellena geocodificando la dirección</b> (ADR-011 §2).</li>
 * </ul>
 * Toda agregación territorial publica cuántos registros hay en cada uno (regla 7).
 */
public enum Assignment {

	RESOLVED, AMBIGUOUS, OUTSIDE, NO_POINT;

	/** Si el registro tiene junta asignada (los dos primeros). */
	public boolean hasDistrict() {
		return this == RESOLVED || this == AMBIGUOUS;
	}

}
