package es.zaragoza.observatory.urban.domain;

import java.util.List;

/**
 * Una página de locales con su total, para que quien pinta no tenga que contar (regla 8).
 *
 * @param items locales de la página, en el orden pedido, con sus licencias
 * @param total locales que cumplen el filtro, sin paginar
 * @param page página devuelta, desde 0
 * @param size tamaño de página pedido
 */
public record PremisesPage(List<LicensedPremises> items, long total, int page, int size) {

	public PremisesPage {
		items = items == null ? List.of() : List.copyOf(items);
	}

}
