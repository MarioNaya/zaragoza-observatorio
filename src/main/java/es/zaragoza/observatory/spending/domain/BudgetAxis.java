package es.zaragoza.observatory.spending.domain;

/**
 * Ejes de agregación del presupuesto (S3.2 §9). Todos cuentan <b>partidas</b> y suman euros, y ninguno se
 * solapa: una partida cae en un capítulo, un área, un programa y un órgano, en uno de cada.
 * <p>
 * La diferencia que sí importa es <b>sobre qué se agrega</b>:
 * <ul>
 * <li>{@link #YEAR} recorre la serie y toma <b>la última instantánea de cada año</b>. Sumar las doce fotos de un
 * año contaría el mismo euro doce veces, porque cada foto es acumulada desde enero.</li>
 * <li>los demás agregan <b>dentro de una sola instantánea</b>, la que diga la consulta.</li>
 * </ul>
 * Con {@link #YEAR} sale además, gratis, la serie que la propia API no publica: sus resúmenes anuales empiezan
 * en 2015 y las instantáneas llegan a 2006 (S3.2 §9).
 * <p>
 * No hay eje territorial y no puede haberlo: el gasto presupuestario no se publica por junta (ADR-003 §1).
 */
public enum BudgetAxis {

	/** Un grupo por ejercicio, con la última instantánea publicada de ese año. */
	YEAR,

	/**
	 * Capítulo económico (1..9). Es el eje que enseña que este endpoint <b>no es solo gasto corriente</b>: el
	 * capítulo 6 son inversiones reales.
	 */
	CHAPTER,

	/** Área de gasto. */
	AREA,

	/**
	 * Programa presupuestario. <b>El grupo sin programa sale como tal</b>: la clasificación no existía en
	 * 2010-2014 y llega incompleta en otros seis ejercicios, y esconder esas 32.618 filas borraría el hecho
	 * (S3.2 §7).
	 */
	PROGRAMME,

	/** Órgano gestor. */
	ORGAN

}
