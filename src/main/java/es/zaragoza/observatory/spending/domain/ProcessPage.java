package es.zaragoza.observatory.spending.domain;

import java.util.List;

/**
 * Una página de procesos con su total, para que quien pinta no tenga que contar (regla 8).
 *
 * @param items procesos de la página, en el orden pedido, con sus adjudicaciones, contratos y CPV
 * @param total procesos que cumplen el filtro, sin paginar
 */
public record ProcessPage(List<ContractingProcess> items, long total, int page, int size) {

	public ProcessPage {
		items = items == null ? List.of() : List.copyOf(items);
	}

}
