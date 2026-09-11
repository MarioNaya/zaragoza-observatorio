package es.zaragoza.observatory.spending.domain;

/**
 * Consulta de concesiones: filtros, ordenación y página (regla 8, el backend ordena, filtra y pagina).
 *
 * @param year año de concesión
 * @param callId convocatoria
 * @param beneficiaryId beneficiario, por su seudónimo
 * @param classification clasificación del beneficiario
 * @param naturalPerson {@code true} solo personas físicas, {@code false} solo lo demás, {@code null} todo. Como
 * el {@code internal} de las quejas (ADR-015): no cambia ninguna cifra por defecto, deja quitarlas a quien lee
 * @param titleContains texto que debe contener el título (búsqueda insensible a mayúsculas)
 * @param sort campo de ordenación
 * @param ascending sentido
 * @param page página desde 0
 * @param size tamaño de página
 */
public record GrantQuery(Integer year, Integer callId, String beneficiaryId, String classification,
		Boolean naturalPerson, String titleContains, SortField sort, boolean ascending, int page, int size) {

	public static final int MAX_SIZE = 500;

	public static final int DEFAULT_SIZE = 50;

	public GrantQuery {
		if (page < 0) {
			throw new IllegalArgumentException("page must not be negative");
		}
		if (size <= 0 || size > MAX_SIZE) {
			throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
		}
		sort = sort == null ? SortField.GRANTED_ON : sort;
	}

	/** La concesión más reciente primero, la ordenación por defecto. */
	public static GrantQuery all() {
		return new GrantQuery(null, null, null, null, null, null, SortField.GRANTED_ON, false, 0, DEFAULT_SIZE);
	}

	/** La misma consulta sin paginación, para agregar. */
	public GrantQuery filtersOnly() {
		return new GrantQuery(year, callId, beneficiaryId, classification, naturalPerson, titleContains, sort,
				ascending, 0, MAX_SIZE);
	}

	/**
	 * Ordenaciones admitidas. El identificador es el desempate estable de todas: sin él, dos concesiones con el
	 * mismo importe o la misma fecha pueden cambiar de página entre peticiones.
	 */
	public enum SortField {
		ID, GRANTED, REQUESTED, GRANTED_ON, REQUESTED_ON
	}

}
