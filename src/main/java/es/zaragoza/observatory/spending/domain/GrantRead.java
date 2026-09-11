package es.zaragoza.observatory.spending.domain;

import java.util.List;

/**
 * Una concesión tal como se lee, con lo que le presta su convocatoria y su beneficiario. El backend hace el
 * cruce (regla 8): quien pinta no encadena tres peticiones para poner un nombre.
 *
 * @param grant la concesión
 * @param call título de su convocatoria, {@code null} si el origen no la trae
 * @param line línea de financiación de la convocatoria
 * @param type tipo de procedimiento de la convocatoria
 * @param beneficiaryId seudónimo del beneficiario, {@code null} si no hay enlace (los 2.609 de 2013-2014)
 * @param beneficiary razón social, {@code null} si es una persona física o si no hay enlace
 * @param naturalPerson si el beneficiario es una persona física; {@code null} si no hay enlace
 * @param classification clasificación del beneficiario
 */
public record GrantRead(Grant grant, String call, String line, String type, String beneficiaryId,
		String beneficiary, Boolean naturalPerson, String classification) {

	/** Una página de concesiones con su total, para que quien pinta no tenga que contar (regla 8). */
	public record Page(List<GrantRead> items, long total, int page, int size) {

		public Page {
			items = items == null ? List.of() : List.copyOf(items);
		}

	}

}
