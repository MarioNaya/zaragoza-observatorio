package es.zaragoza.observatory.spending.domain;

import java.time.LocalDate;

/**
 * Puerto de lectura de una instantánea del presupuesto. Vive aquí y lo implementa {@code infrastructure}
 * (hexagonal, regla 4): el dominio sabe que una instantánea se lee, no que se lee por HTTP en tres páginas de
 * 500 con {@code sort=id asc}.
 */
public interface BudgetSource {

	/** Lee una instantánea entera. Nunca lanza: los desenlaces son el valor de retorno. */
	BudgetSnapshotRead read(LocalDate date);

}
