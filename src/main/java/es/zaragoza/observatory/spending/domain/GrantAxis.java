package es.zaragoza.observatory.spending.domain;

/**
 * Ejes de agregación de las subvenciones (ADR-018 §6). Todos cuentan <b>concesiones</b> y suman importe
 * concedido, y cada grupo publica además cuántas de sus concesiones van a una persona física y a cuántos
 * beneficiarios distintos llega.
 * <p>
 * No hay eje territorial y no puede haberlo: las subvenciones no se publican por junta (ADR-003 §1, S0.6).
 */
public enum GrantAxis {

	/**
	 * Un grupo por año de concesión. Aquí <b>sí</b> se suma la serie entera, al revés que en el presupuesto: una
	 * concesión es un hecho datado y no una foto acumulada, así que sumar los años no cuenta dos veces nada.
	 */
	YEAR,

	/** Convocatoria. */
	CALL,

	/** Línea de financiación de la convocatoria. */
	LINE,

	/** Tipo de procedimiento: concurrencia competitiva, directa o nominativa. */
	TYPE,

	/** Cargo gestor de la convocatoria. */
	MANAGER,

	/**
	 * Clasificación del beneficiario. Es el eje que dice qué parte del dinero va a personas físicas sin nombrar a
	 * ninguna, y el grupo de las concesiones <b>sin beneficiario</b> sale como tal: son los 2.609 registros que
	 * solo publica la v1 y para los que no hay enlace (ADR-018 §7).
	 */
	CLASSIFICATION,

	/**
	 * Beneficiario. El grupo lleva el seudónimo como clave y, como etiqueta, la razón social <b>solo si no es una
	 * persona física</b>: en ese caso la etiqueta es nula y quien lee ve un beneficiario contado, no nombrado.
	 */
	BENEFICIARY

}
