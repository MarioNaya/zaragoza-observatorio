package es.zaragoza.observatory.territory.domain;

/**
 * Catálogo <b>cerrado</b> de denominadores (ADR-019 §3). Está separado del de medidas a propósito y la distinción
 * no es cosmética: un denominador es una base de exposición que la regla 7 obliga a publicar, y por eso el
 * producto sí divide por él. Otra medida no lo es, y por eso no se divide una medida por otra (ADR-019 §4).
 */
public enum Denominator {

	/**
	 * Padrón de la junta. El año <b>nunca es implícito</b>: viaja en cada fila, porque la serie municipal tiene
	 * 2020, 2021, 2022 y 2024 y no 2023 (S2.1), y la junta que no lo tenga sale sin tasa y sin denominador. El
	 * hueco se ve; no se interpola (ADR-015).
	 */
	POPULATION,

	/** Sin denominador: solo cifras absolutas. */
	NONE

}
