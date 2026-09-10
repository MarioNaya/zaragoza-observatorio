package es.zaragoza.observatory.spending.domain;

/**
 * Un código CPV del proceso, sacado de la clasificación de sus artículos. Solo lo trae el 42,7 % de los procesos
 * (2.401 de 5.606), así que <b>no sustituye al texto del objeto del contrato</b>.
 * <p>
 * Un proceso puede tener varios: {@code main} marca el {@code classification} principal del artículo y los demás
 * vienen de {@code additionalClassifications}. Por eso, al agregar por CPV, la suma de los grupos no es el total
 * de procesos, y la respuesta lo declara (ADR-017 §7).
 *
 * @param code código CPV tal como lo publica el origen
 * @param description descripción oficial que acompaña al código
 * @param main si es la clasificación principal de algún artículo
 */
public record Cpv(String code, String description, boolean main) {

	public Cpv {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("cpv code must not be blank");
		}
		code = code.strip();
		description = description == null || description.isBlank() ? null : description.strip();
	}

}
