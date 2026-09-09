package es.zaragoza.observatory.urban.domain;

/**
 * Cuántos locales con licencia de un año se pudieron situar, sobre <b>todos</b> los de ese año.
 * <p>
 * Es el mismo argumento que en {@code citizen} (ADR-015): dentro de un grupo territorial la cobertura vale
 * siempre 1 por construcción —sin punto no hay junta—, así que un 1,00 por grupo no dice nada y lo que hay que
 * mirar es qué parte del año entero se pudo situar. En esta fuente la cobertura global es del 89,4 %, mucho
 * mejor que la de las quejas, pero no está medida por año: por eso se publica.
 *
 * @param year año de la licencia
 * @param total locales con alguna licencia de ese año que pasan los filtros
 * @param withPoint los que traen punto
 * @param assigned los que además cayeron dentro de una junta (los {@code OUTSIDE} tienen punto y no junta)
 */
public record YearCoverage(int year, long total, long withPoint, long assigned) {

	/** Proporción del año que se pudo situar, entre 0 y 1; 0 si el año no tiene registros. */
	public double pointCoverage() {
		return total == 0 ? 0 : (double) withPoint / total;
	}

}
