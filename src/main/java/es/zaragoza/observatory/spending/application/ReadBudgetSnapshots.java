package es.zaragoza.observatory.spending.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.zaragoza.observatory.spending.domain.BudgetRepository;
import es.zaragoza.observatory.spending.domain.BudgetSnapshotRead;
import es.zaragoza.observatory.spending.domain.BudgetSource;
import es.zaragoza.observatory.spending.domain.DueSnapshot;
import es.zaragoza.observatory.spending.domain.SnapshotSchedule;
import es.zaragoza.observatory.spending.domain.SnapshotStatus;

/**
 * Lee por lotes las instantáneas del presupuesto a las que les toca (S3.2 §11), el mismo patrón que
 * {@link ReadReleases} y que el muestreo observado de {@code catalog} (ADR-005): el contrato de
 * {@code ingestion} describe <b>una</b> URL, y aquí hacen falta 396 peticiones para el histórico.
 * <p>
 * Cada instantánea se guarda en su propia transacción. Y como una instantánea ya publicada no se reescribe
 * (S3.2 §3), al cargarla se congela: el histórico se paga una vez y después solo se relee la más reciente.
 * <p>
 * <b>Un fallo de red no es un 404.</b> Una lectura que no concluye solo aplaza el siguiente intento; no cambia
 * el estado de la instantánea ni cuenta como intento. Si más de la mitad del lote no concluye, el lote se corta.
 */
public class ReadBudgetSnapshots {

	private static final Logger log = LoggerFactory.getLogger(ReadBudgetSnapshots.class);

	/** Antes de cortar por fallos hay que haber intentado lo suficiente como para que la proporción signifique algo. */
	private static final int MIN_ATTEMPTS_BEFORE_GIVING_UP = 4;

	private final BudgetRepository budget;
	private final BudgetSource source;
	private final SnapshotSchedule schedule;
	private final Clock clock;

	public ReadBudgetSnapshots(BudgetRepository budget, BudgetSource source, SnapshotSchedule schedule,
			Clock clock) {
		this.budget = Objects.requireNonNull(budget);
		this.source = Objects.requireNonNull(source);
		this.schedule = Objects.requireNonNull(schedule);
		this.clock = Objects.requireNonNull(clock);
	}

	public Summary readDue(int batchSize) {
		List<DueSnapshot> due = budget.due(clock.instant(), batchSize);
		if (due.isEmpty()) {
			return new Summary(0, 0, 0, 0, 0, 0, false);
		}
		Optional<LocalDate> latest = budget.latestCensused();
		int loaded = 0;
		int empty = 0;
		int absent = 0;
		int unreadable = 0;
		int lines = 0;
		boolean abandoned = false;
		int read = 0;
		for (DueSnapshot pending : due) {
			BudgetSnapshotRead result = source.read(pending.date());
			Instant attemptedAt = clock.instant();
			read++;
			if (!result.conclusive()) {
				unreadable++;
				budget.recordFailedAttempt(pending.date(), attemptedAt,
						schedule.nextAttempt(null, pending.attempts(), attemptedAt, false));
				if (read >= MIN_ATTEMPTS_BEFORE_GIVING_UP && unreadable * 2 > read) {
					log.warn("spending: {} de {} lecturas del presupuesto no concluyeron; se corta el lote",
							unreadable, read);
					abandoned = true;
					break;
				}
				continue;
			}
			int attempts = pending.attempts() + 1;
			boolean isLatest = latest.map(pending.date()::equals).orElse(false);
			if (pending.status() == SnapshotStatus.LOADED && result.status() != SnapshotStatus.LOADED) {
				// Una instantánea que estaba cargada y ahora no responde. Se conservan sus partidas —vaciarlas
				// convertiría un tropiezo de la fuente en pérdida de datos— pero conviene enterarse.
				log.warn("spending: la instantánea {} estaba cargada y ahora responde {}; se conservan sus partidas",
						pending.date(), result.status());
			}
			budget.recordSnapshot(result, attempts, attemptedAt,
					schedule.nextAttempt(result.status(), attempts, attemptedAt, isLatest));
			switch (result.status()) {
				case LOADED -> {
					loaded++;
					lines += result.lines().size();
					if (result.reportedCount() >= 0 && result.reportedCount() != result.lines().size()) {
						log.warn("spending: la instantánea {} dijo tener {} partidas y trajo {}", pending.date(),
								result.reportedCount(), result.lines().size());
					}
				}
				case EMPTY -> empty++;
				case ABSENT -> absent++;
				case PENDING -> throw new IllegalStateException("una lectura concluyente no puede devolver PENDING");
			}
		}
		if (log.isInfoEnabled()) {
			log.info("spending: lote de presupuesto: {} pedidas, {} cargadas ({} partidas), {} vacías, {} ausentes, "
					+ "{} sin concluir{}", read, loaded, lines, empty, absent, unreadable,
					abandoned ? " (lote cortado)" : "");
		}
		return new Summary(read, loaded, lines, empty, absent, unreadable, abandoned);
	}

	/**
	 * @param requested instantáneas pedidas en el lote
	 * @param loaded instantáneas que trajeron partidas
	 * @param lines partidas cargadas en el lote
	 * @param empty respondieron 200 sin partidas
	 * @param absent no respondieron a su URL
	 * @param unreadable no concluyeron; ni cambian de estado ni cuentan como intento
	 * @param abandoned el lote se cortó porque la fuente no respondía
	 */
	public record Summary(int requested, int loaded, int lines, int empty, int absent, int unreadable,
			boolean abandoned) {
	}

}
