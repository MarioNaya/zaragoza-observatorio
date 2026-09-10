package es.zaragoza.observatory.spending.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de persistencia del presupuesto. Como en la contratación, hay <b>dos escrituras</b> porque hay dos
 * pasos y no se pisan: el censo dice qué instantáneas existen y la lectura trae sus partidas. Una instantánea
 * censada y sin leer es una fila legítima.
 * <p>
 * El upsert es idempotente (regla 5): reejecutar el censo no duplica nada y releer una instantánea reemplaza sus
 * partidas enteras, porque la fuente las publica completas.
 * <p>
 * <b>Nada se borra nunca.</b> Una fecha que dejara de aparecer en el censo conserva sus partidas y su
 * {@code lastSeenAt}, igual que las fichas dadas de baja de ADR-013.
 */
public interface BudgetRepository {

	/**
	 * Da de alta las fechas nuevas como {@link SnapshotStatus#PENDING} y refresca {@code lastSeenAt} de las ya
	 * conocidas. No toca el contenido de ninguna.
	 *
	 * @return cuántas fechas se han visto
	 */
	int upsertCensus(List<LocalDate> dates, Instant seenAt);

	/** Instantáneas a las que les toca lectura, las más antiguas primero. */
	List<DueSnapshot> due(Instant now, int limit);

	/**
	 * Guarda el resultado de una lectura concluyente. Cuando trae partidas, <b>reemplaza enteras</b> las de esa
	 * instantánea y recalcula sus totales materializados.
	 *
	 * @param nextAttemptAt cuándo toca la siguiente lectura, o {@code null} para congelarla
	 */
	void recordSnapshot(BudgetSnapshotRead read, int attempts, Instant attemptedAt, Instant nextAttemptAt);

	/**
	 * Guarda un intento que no concluyó. <b>No cambia el estado ni cuenta como intento</b>: un fallo de red no
	 * es un 404.
	 */
	void recordFailedAttempt(LocalDate date, Instant attemptedAt, Instant nextAttemptAt);

	/** La fecha más reciente del censo, que es la única instantánea que se relee. */
	Optional<LocalDate> latestCensused();

	/** La instantánea más reciente ya cargada: la que contestan por defecto el listado y las agregaciones. */
	Optional<LocalDate> latestLoaded();

	/** El censo entero con su estado y sus totales, de la más reciente a la más antigua. */
	List<BudgetSnapshot> snapshots();

	Optional<BudgetSnapshot> snapshot(LocalDate date);

	/** Una página de partidas de <b>una</b> instantánea, en el orden pedido (regla 8). */
	BudgetLinePage searchLines(BudgetQuery query);

	/**
	 * Agrega por el eje pedido. En {@link BudgetAxis#YEAR} cada grupo sale de la última instantánea cargada de su
	 * año; en los demás, de la instantánea de la consulta.
	 */
	List<BudgetBucket> aggregate(BudgetAxis axis, BudgetQuery filters);

	BudgetTotals totals();

}
