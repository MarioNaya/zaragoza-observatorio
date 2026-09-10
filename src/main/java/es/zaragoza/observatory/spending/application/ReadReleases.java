package es.zaragoza.observatory.spending.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;
import es.zaragoza.observatory.spending.domain.DueRelease;
import es.zaragoza.observatory.spending.domain.ReleaseContent;
import es.zaragoza.observatory.spending.domain.ReleaseRead;
import es.zaragoza.observatory.spending.domain.ReleaseSource;
import es.zaragoza.observatory.spending.domain.ReleaseStatus;
import es.zaragoza.observatory.spending.domain.RetrySchedule;
import es.zaragoza.observatory.spending.domain.Stage;

/**
 * Lee el detalle de los procesos a los que les toca, por lotes (ADR-017 §5). Es el mismo patrón que el muestreo
 * observado de {@code catalog} (ADR-005) y por la misma razón: el contrato de {@code ingestion} describe una
 * URL, y aquí hacen falta 8.001 peticiones para el histórico.
 * <p>
 * Cada lectura se guarda en su propia transacción, no todas juntas: un lote de 200 que fallara al final tiraría
 * 199 lecturas buenas y las volvería a pedir en el tick siguiente.
 * <p>
 * <b>Un fallo de red no es un 404.</b> Una lectura que no concluye solo aplaza el siguiente intento; no cambia
 * el estado del proceso ni cuenta como intento. Y si más de la mitad del lote no concluye, el lote se corta: la
 * fuente está caída y seguir pidiendo 200 veces cada diez minutos no la va a levantar.
 */
public class ReadReleases {

	private static final Logger log = LoggerFactory.getLogger(ReadReleases.class);

	/** Antes de cortar por fallos hay que haber intentado lo suficiente como para que la proporción signifique algo. */
	private static final int MIN_ATTEMPTS_BEFORE_GIVING_UP = 10;

	private final ContractingProcessRepository processes;
	private final ReleaseSource source;
	private final RetrySchedule schedule;
	private final Clock clock;

	public ReadReleases(ContractingProcessRepository processes, ReleaseSource source, RetrySchedule schedule,
			Clock clock) {
		this.processes = Objects.requireNonNull(processes);
		this.source = Objects.requireNonNull(source);
		this.schedule = Objects.requireNonNull(schedule);
		this.clock = Objects.requireNonNull(clock);
	}

	public Summary readDue(int batchSize) {
		List<DueRelease> due = processes.due(clock.instant(), batchSize);
		if (due.isEmpty()) {
			return new Summary(0, 0, 0, 0, 0, false);
		}
		int published = 0;
		int empty = 0;
		int absent = 0;
		int unreadable = 0;
		boolean abandoned = false;
		int read = 0;
		for (DueRelease pending : due) {
			ReleaseRead result = source.read(pending.ocid());
			Instant attemptedAt = clock.instant();
			read++;
			if (!result.conclusive()) {
				unreadable++;
				processes.recordFailedAttempt(pending.ocid(),
						attemptedAt, schedule.nextAttempt(null, pending.attempts(), attemptedAt, false));
				if (read >= MIN_ATTEMPTS_BEFORE_GIVING_UP && unreadable * 2 > read) {
					log.warn("spending: {} de {} lecturas del lote no concluyeron; se corta el lote", unreadable,
							read);
					abandoned = true;
					break;
				}
				continue;
			}
			int attempts = pending.attempts() + 1;
			ReleaseContent content = result.content();
			if (pending.status() == ReleaseStatus.PUBLISHED && content == null) {
				// Un proceso que tenía release y ahora no lo tiene. El contenido conocido se conserva —vaciarlo
				// convertiría un tropiezo de la fuente en pérdida de datos— pero conviene enterarse.
				log.warn("spending: {} tenía release y ahora responde {}; se conserva el contenido conocido",
						pending.ocid(), result.status());
			}
			boolean tenderActive = content != null && content.tender().isActive();
			Stage stage = content == null ? null
					: Stage.derive(content.tender().status(),
							content.contracts().stream().anyMatch(contract -> contract.signedOn() != null));
			processes.recordRelease(pending.ocid(), result.status(), content, stage, attempts, attemptedAt,
					schedule.nextAttempt(result.status(), attempts, attemptedAt, tenderActive));
			switch (result.status()) {
				case PUBLISHED -> published++;
				case EMPTY -> empty++;
				case ABSENT -> absent++;
				case PENDING -> throw new IllegalStateException("una lectura no puede devolver PENDING");
			}
		}
		if (log.isInfoEnabled()) {
			log.info("spending: lote de detalle: {} pedidos, {} con release, {} vacíos, {} sin publicar, {} sin "
					+ "concluir{}", read, published, empty, absent, unreadable, abandoned ? " (lote cortado)" : "");
		}
		return new Summary(read, published, empty, absent, unreadable, abandoned);
	}

	/**
	 * @param requested procesos a los que se pidió el detalle en este lote
	 * @param published respondieron 200 con release
	 * @param empty respondieron 200 sin release
	 * @param absent respondieron 404: su release aún no está publicado
	 * @param unreadable no concluyeron; ni cambian de estado ni cuentan como intento
	 * @param abandoned el lote se cortó porque la fuente no respondía
	 */
	public record Summary(int requested, int published, int empty, int absent, int unreadable, boolean abandoned) {
	}

}
