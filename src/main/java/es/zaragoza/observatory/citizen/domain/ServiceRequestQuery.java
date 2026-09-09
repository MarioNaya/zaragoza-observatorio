package es.zaragoza.observatory.citizen.domain;

import java.time.Instant;

import es.zaragoza.observatory.geo.Assignment;

/**
 * Criterios de consulta de quejas (regla 8: el backend ordena, filtra y pagina). Todos los filtros son
 * opcionales; los nulos no filtran.
 * <p>
 * {@code districtId} filtra por la junta <b>resuelta</b>, no por la declarada: filtrar por el nombre que trae el
 * origen daría un recuento distinto y sin decirlo (ADR-011 §3). Quien quiera ver la discrepancia tiene el campo
 * declarado en cada registro y el recuento en el resumen.
 *
 * @param districtId junta resuelta
 * @param serviceCode categoría del origen
 * @param status estado
 * @param assignment cómo quedó la asignación territorial ({@code NO_POINT} incluido: los sin asignar se pueden
 * pedir explícitamente, no son un residuo invisible)
 * @param internal qué hacer con los servicios {@code INTERNAL}, que no son quejas ciudadanas: por defecto cuentan
 * (ADR-015). Nulo equivale a {@link InternalServices.Filter#INCLUDE}
 * @param from alta desde (inclusive)
 * @param to alta hasta (exclusive)
 * @param sortField campo de ordenación ya validado contra la lista blanca
 * @param ascending sentido
 * @param page página desde 0
 * @param size tamaño de página
 */
public record ServiceRequestQuery(Integer districtId, String serviceCode, ServiceRequestStatus status,
		Assignment assignment, InternalServices.Filter internal, Instant from, Instant to, SortField sortField,
		boolean ascending, int page, int size) {

	/** Campos por los que se puede ordenar (lista blanca explícita, regla 8). */
	public enum SortField {

		REQUESTED_AT("requested_at"), UPDATED_AT("updated_at"), ID("source_id");

		private final String column;

		SortField(String column) {
			this.column = column;
		}

		public String column() {
			return column;
		}
	}

	public static final int MAX_SIZE = 200;

	public ServiceRequestQuery {
		if (page < 0) {
			throw new IllegalArgumentException("page must not be negative");
		}
		if (size <= 0 || size > MAX_SIZE) {
			throw new IllegalArgumentException("size must be in 1.." + MAX_SIZE);
		}
		if (sortField == null) {
			sortField = SortField.REQUESTED_AT;
		}
		if (internal == null) {
			internal = InternalServices.Filter.INCLUDE;
		}
		if (from != null && to != null && !from.isBefore(to)) {
			throw new IllegalArgumentException("from must be before to");
		}
	}

	/** Los mismos filtros sin paginación, para agregar. */
	public ServiceRequestQuery filtersOnly() {
		return new ServiceRequestQuery(districtId, serviceCode, status, assignment, internal, from, to, sortField,
				ascending, 0, MAX_SIZE);
	}

	public static ServiceRequestQuery all() {
		return new ServiceRequestQuery(null, null, null, null, InternalServices.Filter.INCLUDE, null, null,
				SortField.REQUESTED_AT, false, 0, MAX_SIZE);
	}

}
