package es.zaragoza.observatory.citizen.domain;

/**
 * Ejes de agregación admitidos (SPEC.md §4.7). No hay sección censal porque las secciones no están
 * implementadas todavía (S0.4, ADR-011), y no se ofrece un eje que no se pueda calcular.
 */
public enum AggregationAxis {

	/** Por junta resuelta. Los registros sin junta salen aparte, nunca repartidos (regla 7). */
	DISTRICT,
	/** Por categoría del origen ({@code service_code} con su {@code service_name}). */
	CATEGORY,
	/** Por mes de alta, {@code yyyy-MM}. */
	MONTH

}
