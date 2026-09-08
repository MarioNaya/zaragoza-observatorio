package es.zaragoza.observatory.citizen.domain;

import java.util.List;

/**
 * Una página de resultados con su total, para que quien pinta no tenga que contar (regla 8).
 *
 * @param items registros de la página, en el orden pedido
 * @param total registros que cumplen el filtro, sin paginar
 * @param page página devuelta, desde 0
 * @param size tamaño de página pedido
 */
public record ServiceRequestPage(List<ServiceRequest> items, long total, int page, int size) {

	public ServiceRequestPage {
		items = items == null ? List.of() : List.copyOf(items);
	}

}
