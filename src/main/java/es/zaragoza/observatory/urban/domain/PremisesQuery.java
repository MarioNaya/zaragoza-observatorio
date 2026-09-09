package es.zaragoza.observatory.urban.domain;

import java.time.Instant;

import es.zaragoza.observatory.geo.Assignment;

/**
 * Criterios de consulta de locales (regla 8: el backend ordena, filtra y pagina). Todos los filtros son
 * opcionales; los nulos no filtran.
 * <p>
 * {@code districtId} filtra por la junta <b>resuelta</b>. Aquí no hay otra: esta fuente no declara junta
 * (ADR-016 §5).
 *
 * @param districtId junta resuelta
 * @param iaeCode epígrafe exacto
 * @param iaeSection sección del epígrafe
 * @param iaeGroup agrupación del epígrafe, el nivel por el que tiene sentido agregar
 * @param statusCode {@code estado} del origen, sin traducir
 * @param saturatedZone código de zona saturada
 * @param assignment estado de la resolución territorial ({@code NO_POINT} incluido: los sin asignar se pueden
 * pedir explícitamente, no son un residuo invisible)
 * @param licenceYear año de alguna de sus licencias
 * @param from alta del local desde (inclusive)
 * @param to alta del local hasta (exclusive)
 * @param sortField campo de ordenación ya validado contra la lista blanca
 * @param ascending sentido
 * @param page página desde 0
 * @param size tamaño de página
 */
public record PremisesQuery(Integer districtId, String iaeCode, Integer iaeSection, Integer iaeGroup,
		Integer statusCode, String saturatedZone, Assignment assignment, Integer licenceYear, Instant from,
		Instant to, SortField sortField, boolean ascending, int page, int size) {

	/** Campos por los que se puede ordenar (lista blanca explícita, regla 8). */
	public enum SortField {

		CREATED_AT("p.created_at"), UPDATED_AT("p.updated_at"), ID("p.source_id");

		private final String column;

		SortField(String column) {
			this.column = column;
		}

		public String column() {
			return column;
		}
	}

	public static final int MAX_SIZE = 200;

	public PremisesQuery {
		if (page < 0) {
			throw new IllegalArgumentException("page must not be negative");
		}
		if (size <= 0 || size > MAX_SIZE) {
			throw new IllegalArgumentException("size must be in 1.." + MAX_SIZE);
		}
		if (sortField == null) {
			sortField = SortField.CREATED_AT;
		}
		if (from != null && to != null && !from.isBefore(to)) {
			throw new IllegalArgumentException("from must be before to");
		}
	}

	/** Los mismos filtros sin paginación, para agregar. */
	public PremisesQuery filtersOnly() {
		return new PremisesQuery(districtId, iaeCode, iaeSection, iaeGroup, statusCode, saturatedZone, assignment,
				licenceYear, from, to, sortField, ascending, 0, MAX_SIZE);
	}

	public static PremisesQuery all() {
		return new PremisesQuery(null, null, null, null, null, null, null, null, null, null, SortField.CREATED_AT,
				false, 0, MAX_SIZE);
	}

}
