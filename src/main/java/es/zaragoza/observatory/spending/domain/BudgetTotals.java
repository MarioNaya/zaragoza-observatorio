package es.zaragoza.observatory.spending.domain;

import java.time.LocalDate;
import java.util.Map;

/**
 * El universo del presupuesto con sus huecos, para el resumen de la API. Los recuentos por estado de lectura no
 * son telemetría: mientras la carga inicial avanza, dicen qué parte de la serie se está mirando.
 *
 * @param snapshots instantáneas censadas
 * @param byStatus cuántas hay en cada situación de lectura
 * @param lines partidas cargadas en total
 * @param firstSnapshot la más antigua del censo
 * @param lastSnapshot la más reciente del censo
 * @param latestLoaded la más reciente ya cargada, que es la que contestan por defecto listado y agregaciones
 * @param latestTotals los ocho importes de esa instantánea
 * @param redactedHeadings partidas cuyo nombre se guardó redactado por nombrar a una persona física (S3.2 §8)
 * @param linesWithoutProgramme partidas sin programa presupuestario: la clasificación no existía en 2010-2014
 */
public record BudgetTotals(long snapshots, Map<SnapshotStatus, Long> byStatus, long lines, LocalDate firstSnapshot,
		LocalDate lastSnapshot, LocalDate latestLoaded, BudgetAmounts latestTotals, long redactedHeadings,
		long linesWithoutProgramme) {

	public BudgetTotals {
		byStatus = byStatus == null ? Map.of() : Map.copyOf(byStatus);
	}

	/** Instantáneas censadas que todavía no se han leído. Durante la carga inicial es la mayoría. */
	public long pending() {
		return byStatus.getOrDefault(SnapshotStatus.PENDING, 0L);
	}

}
