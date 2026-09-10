package es.zaragoza.observatory.spending.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Una página de partidas con su total, para que quien pinta no tenga que contar (regla 8).
 * <p>
 * {@code snapshotDate} es parte de la respuesta, no un detalle: el listado siempre describe <b>una</b> foto, y
 * cuál es no puede quedar implícito.
 *
 * @param snapshotDate instantánea a la que pertenecen todas las filas; {@code null} si aún no hay ninguna cargada
 * @param items partidas de la página, en el orden pedido
 * @param total partidas que cumplen el filtro, sin paginar
 */
public record BudgetLinePage(LocalDate snapshotDate, List<BudgetLine> items, long total, int page, int size) {

	public BudgetLinePage {
		items = items == null ? List.of() : List.copyOf(items);
	}

}
