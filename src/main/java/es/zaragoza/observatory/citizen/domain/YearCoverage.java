package es.zaragoza.observatory.citizen.domain;

/**
 * Cuántas quejas de un año se pudieron situar, sobre <b>todas</b> las de ese año.
 * <p>
 * Es el dato que hace falta para leer una serie por junta y año, y no se puede sacar de los grupos: en los ejes
 * territoriales solo se agrupa lo que tiene junta, y sin punto no hay junta (ADR-011 §2), así que la cobertura
 * <i>dentro</i> de un grupo es siempre del 100 % por construcción y no dice nada. La cobertura que importa es la
 * del año entero, y va aparte para que se vea de qué está hecha cada columna de la serie: en 2013 se situó el
 * 86,5 % de las quejas y en 2015 el 18 % (S2.2), así que comparar las dos cifras sin mirar esto es comparar dos
 * coberturas distintas.
 *
 * @param year año de alta, en hora local de Zaragoza
 * @param total quejas de ese año que pasan los filtros
 * @param withPoint las que traen punto
 * @param assigned las que además cayeron dentro de una junta (las de {@code OUTSIDE} tienen punto y no junta)
 */
public record YearCoverage(int year, long total, long withPoint, long assigned) {

	/** Proporción del año que se pudo situar, entre 0 y 1; 0 si el año no tiene registros. */
	public double pointCoverage() {
		return total == 0 ? 0 : (double) withPoint / total;
	}

}
