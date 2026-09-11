package es.zaragoza.observatory.territory.domain;

/**
 * Criterio de ordenación de las filas del cruce. Explícito siempre, con lista blanca y desempate estable
 * (regla 8): ningún endpoint devuelve listas sin criterio.
 * <p>
 * Solo se puede ordenar por la junta o por una de las medidas <b>pedidas</b>: ordenar por una columna que no
 * está en la respuesta daría un orden que quien lee no puede comprobar.
 *
 * @param measure medida por la que ordenar, o {@code null} para ordenar por id de junta
 * @param ascending sentido
 */
public record CrossTabSort(Measure measure, boolean ascending) {

	/** Por junta ascendente: el orden por defecto, el mismo de {@code GET /geo/districts}. */
	public static CrossTabSort byDistrict() {
		return new CrossTabSort(null, true);
	}

	public boolean byMeasure() {
		return measure != null;
	}

}
