package es.zaragoza.observatory.spending.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Resultado de leer una instantánea. Distingue los tres desenlaces de la fuente —trajo partidas, respondió sin
 * ellas, no respondió— de la <b>cuarta posibilidad, que no es un desenlace</b>: la lectura no concluyó (red,
 * 5xx, JSON ilegible). Confundirlas convertiría una caída de la fuente en «esta instantánea no existe».
 *
 * @param date instantánea leída
 * @param status desenlace; {@link SnapshotStatus#PENDING} cuando la lectura no concluyó
 * @param lines partidas leídas, vacío si no hay
 * @param reportedCount {@code totalCount} de la primera página, o -1 si no se pudo saber
 * @param conclusive si la fuente contestó algo que permita decidir
 */
public record BudgetSnapshotRead(LocalDate date, SnapshotStatus status, List<BudgetLine> lines, int reportedCount,
		boolean conclusive) {

	public BudgetSnapshotRead {
		lines = lines == null ? List.of() : List.copyOf(lines);
	}

	public static BudgetSnapshotRead loaded(LocalDate date, List<BudgetLine> lines, int reportedCount) {
		return new BudgetSnapshotRead(date, SnapshotStatus.LOADED, lines, reportedCount, true);
	}

	public static BudgetSnapshotRead empty(LocalDate date) {
		return new BudgetSnapshotRead(date, SnapshotStatus.EMPTY, List.of(), 0, true);
	}

	public static BudgetSnapshotRead absent(LocalDate date) {
		return new BudgetSnapshotRead(date, SnapshotStatus.ABSENT, List.of(), -1, true);
	}

	public static BudgetSnapshotRead unreadable(LocalDate date) {
		return new BudgetSnapshotRead(date, SnapshotStatus.PENDING, List.of(), -1, false);
	}

}
