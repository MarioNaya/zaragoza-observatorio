package es.zaragoza.observatory.spending.domain;

import java.time.LocalDate;

/**
 * Consulta del presupuesto: filtros, ordenación y página (regla 8, el backend ordena y pagina).
 * <p>
 * <b>{@code snapshotDate} no es un filtro más</b>: es la foto que se está mirando. Sin él, un listado de partidas
 * mezclaría las 140 instantáneas y una suma contaría el mismo euro hasta 140 veces. Cuando no se pide una fecha
 * concreta, el adaptador resuelve la más reciente cargada y la respuesta la declara.
 *
 * @param snapshotDate instantánea a la que se refiere la consulta; {@code null} = la más reciente cargada
 * @param chapterId capítulo económico
 * @param areaId área de gasto
 * @param programmeId programa presupuestario
 * @param organId órgano gestor
 * @param headingContains texto que debe contener el nombre de la partida (búsqueda insensible a mayúsculas)
 * @param sort campo de ordenación
 * @param ascending sentido
 * @param page página desde 0
 * @param size tamaño de página
 */
public record BudgetQuery(LocalDate snapshotDate, Integer chapterId, String areaId, String programmeId,
		String organId, String headingContains, SortField sort, boolean ascending, int page, int size) {

	public static final int MAX_SIZE = 500;

	public static final int DEFAULT_SIZE = 50;

	public BudgetQuery {
		if (page < 0) {
			throw new IllegalArgumentException("page must not be negative");
		}
		if (size <= 0 || size > MAX_SIZE) {
			throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
		}
		sort = sort == null ? SortField.OBLIGATIONS : sort;
	}

	/** Todas las partidas de la instantánea más reciente, la ordenación por defecto. */
	public static BudgetQuery all() {
		return new BudgetQuery(null, null, null, null, null, null, SortField.OBLIGATIONS, false, 0, DEFAULT_SIZE);
	}

	/** La misma consulta sin paginación, para agregar. */
	public BudgetQuery filtersOnly() {
		return new BudgetQuery(snapshotDate, chapterId, areaId, programmeId, organId, headingContains, sort,
				ascending, 0, MAX_SIZE);
	}

	public BudgetQuery on(LocalDate date) {
		return new BudgetQuery(date, chapterId, areaId, programmeId, organId, headingContains, sort, ascending, page,
				size);
	}

	/**
	 * Ordenaciones admitidas. Los cuatro importes que significan algo distinto, más el código de partida como
	 * desempate estable: sin desempate, dos partidas con el mismo importe pueden cambiar de página entre
	 * peticiones, que es justo el defecto que tiene la fuente (S3.2 §2).
	 */
	public enum SortField {
		CONCEPT, CREDIT_FINAL, COMMITTED, OBLIGATIONS, PAYMENTS
	}

}
