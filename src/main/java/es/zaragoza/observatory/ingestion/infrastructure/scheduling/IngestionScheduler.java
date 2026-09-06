package es.zaragoza.observatory.ingestion.infrastructure.scheduling;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.application.PurgeRawPayloads;

/**
 * Planificador (SPEC.md §4.5 paso 1, §5): en cada tick recorre los {@link IngestionJob} registrados por los módulos
 * de dominio y ejecuta, en serie, los que llevan más de su intervalo sin una ejecución con éxito. Con
 * {@code spring.task.scheduling.pool.size=1} no hay solapamientos (ADR-004, ShedLock aplazado).
 */
@Component
@ConditionalOnProperty(prefix = "zaragoza.ingestion.scheduler", name = "enabled", havingValue = "true",
		matchIfMissing = true)
class IngestionScheduler {

	private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

	private final Ingestion ingestion;
	private final ObjectProvider<IngestionJob> jobs;
	private final PurgeRawPayloads purge;
	private final Clock clock;

	IngestionScheduler(Ingestion ingestion, ObjectProvider<IngestionJob> jobs, PurgeRawPayloads purge, Clock clock) {
		this.ingestion = ingestion;
		this.jobs = jobs;
		this.purge = purge;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "${zaragoza.ingestion.scheduler.initial-delay:PT30S}",
			fixedDelayString = "${zaragoza.ingestion.scheduler.tick:PT10M}")
	void tick() {
		Instant now = clock.instant();
		jobs.orderedStream().filter(job -> isDue(job, now)).forEach(this::runSafely);
	}

	@Scheduled(cron = "${zaragoza.ingestion.scheduler.raw-purge-cron:0 30 4 * * *}")
	void purgeRawPayloads() {
		try {
			purge.purge();
		}
		catch (RuntimeException ex) {
			log.error("raw payload purge failed", ex);
		}
	}

	boolean isDue(IngestionJob job, Instant now) {
		return ingestion.lastSuccessful(job.source().dataset())
				.map(IngestionRunSummary::finishedAt)
				.map(finishedAt -> !finishedAt.plus(job.interval()).isAfter(now))
				.orElse(true);
	}

	private void runSafely(IngestionJob job) {
		try {
			ingestion.run(job);
		}
		catch (RuntimeException ex) {
			// RunIngestion ya registra el fallo en el run; esto solo protege el tick frente a errores inesperados.
			log.error("unexpected error running job {}", job.name(), ex);
		}
	}

}
