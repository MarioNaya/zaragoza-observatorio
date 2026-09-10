package es.zaragoza.observatory.spending.domain;

import java.time.Instant;

/**
 * Criterios de consulta de procesos de contratación (regla 8: el backend ordena, filtra y pagina). Todos los
 * filtros son opcionales; los nulos no filtran.
 * <p>
 * <b>No hay filtro territorial y no puede haberlo</b>: esta fuente no publica localización, medido sobre los
 * 5.622 documentos (ADR-003 §1). Un parámetro de junta aquí prometería un cruce que no existe.
 *
 * @param year año de publicación del proceso
 * @param tenderStatus {@code tender.status} exacto, tal como lo publica el origen
 * @param procurementMethod {@code tender.procurementMethod}
 * @param category {@code tender.mainProcurementCategory}
 * @param stage etapa derivada; ojo, filtrar por etapa <b>esconde</b> los 1.560 procesos que no la tienen, que es
 * justo lo que la respuesta sin filtro deja ver
 * @param releaseStatus situación del detalle, {@code ABSENT} incluido: los procesos sin release se pueden pedir
 * explícitamente, no son un residuo invisible
 * @param cpv código CPV exacto, principal o adicional
 * @param taxId NIF de una adjudicataria (persona jurídica)
 * @param procuringEntity nombre del órgano de contratación, exacto
 * @param inDocumentedList si aparece o no en el listado sin filtro que documenta la API
 * @param titleContains texto contenido en el título de la licitación, sin distinguir mayúsculas ni acentos
 * @param from publicación desde (inclusive)
 * @param to publicación hasta (exclusive)
 */
public record ProcessQuery(Integer year, String tenderStatus, String procurementMethod, String category,
		Stage stage, ReleaseStatus releaseStatus, String cpv, String taxId, String procuringEntity,
		Boolean inDocumentedList, String titleContains, Instant from, Instant to, SortField sortField,
		boolean ascending, int page, int size) {

	/** Campos por los que se puede ordenar (lista blanca explícita, regla 8). */
	public enum SortField {

		PUBLISHED_AT("p.published_at"), TENDER_AMOUNT("p.tender_amount"), AWARDED_AMOUNT("p.awarded_amount"),
		FILE_NUMBER("p.file_number");

		private final String column;

		SortField(String column) {
			this.column = column;
		}

		public String column() {
			return column;
		}
	}

	public static final int MAX_SIZE = 200;

	public ProcessQuery {
		if (page < 0) {
			throw new IllegalArgumentException("page must not be negative");
		}
		if (size <= 0 || size > MAX_SIZE) {
			throw new IllegalArgumentException("size must be in 1.." + MAX_SIZE);
		}
		if (sortField == null) {
			sortField = SortField.PUBLISHED_AT;
		}
		if (from != null && to != null && !from.isBefore(to)) {
			throw new IllegalArgumentException("from must be before to");
		}
	}

	/** Los mismos filtros sin paginación, para agregar. */
	public ProcessQuery filtersOnly() {
		return new ProcessQuery(year, tenderStatus, procurementMethod, category, stage, releaseStatus, cpv, taxId,
				procuringEntity, inDocumentedList, titleContains, from, to, sortField, ascending, 0, MAX_SIZE);
	}

	public static ProcessQuery all() {
		return new ProcessQuery(null, null, null, null, null, null, null, null, null, null, null, null, null,
				SortField.PUBLISHED_AT, false, 0, MAX_SIZE);
	}

}
