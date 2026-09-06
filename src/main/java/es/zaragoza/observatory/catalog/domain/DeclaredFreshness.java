package es.zaragoza.observatory.catalog.domain;

/**
 * Categoría de frescura <em>declarada</em> (SPEC.md §4.6, S0.1 recomendación 3): posición del ratio
 * «días desde {@code modified} / días de la periodicidad declarada» respecto a umbrales configurables
 * ({@link FreshnessPolicy}). No es un juicio sobre el dato: solo compara dos campos que el publicador declara.
 */
public enum DeclaredFreshness {

	/** ratio ≤ umbral 1 (por defecto 1): dentro del periodo declarado. */
	ON_TIME,
	/** ratio ≤ umbral 2 (por defecto 2). */
	SLIGHT_DELAY,
	/** ratio ≤ umbral 3 (por defecto 5). */
	DELAYED,
	/** ratio > umbral 3. */
	NOT_UPDATED,
	/** Sin periodicidad evaluable ({@code NEVER}, {@code IRREG}, {@code P0DT1S}, vacío) o sin {@code modified}. */
	NOT_EVALUABLE

}
