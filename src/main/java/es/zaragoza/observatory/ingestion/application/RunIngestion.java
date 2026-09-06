package es.zaragoza.observatory.ingestion.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination.Mode;
import es.zaragoza.observatory.ingestion.domain.IngestionRun;
import es.zaragoza.observatory.ingestion.domain.IngestionRunRepository;
import es.zaragoza.observatory.ingestion.domain.RawPayload;
import es.zaragoza.observatory.ingestion.domain.RawPayloadStore;
import es.zaragoza.observatory.ingestion.domain.SourceGateway;
import es.zaragoza.observatory.shared.IngestionRunId;

/**
 * Caso de uso «ejecutar una ingesta» (SPEC.md §4.5, pasos 2 a 5): abre el run, pagina la fuente, guarda cada
 * página cruda, se la entrega al job del módulo de dominio y cierra el run con éxito (publicando
 * {@code DatasetIngested}) o con fallo. Idempotente por construcción: cada ejecución es un run nuevo y la
 * persistencia de dominio es un upsert por identificador de origen (regla 5).
 * <p>
 * Sin transacción global: las peticiones HTTP quedan fuera de cualquier transacción; el cierre del run y la
 * publicación del evento son una única transacción ({@link CompleteIngestionRun}).
 */
public class RunIngestion {

	private static final Logger log = LoggerFactory.getLogger(RunIngestion.class);

	private final SourceGateway gateway;
	private final IngestionRunRepository runs;
	private final RawPayloadStore payloads;
	private final CompleteIngestionRun completion;
	private final Clock clock;
	private final Duration pageDelay;
	private final int maxPages;

	public RunIngestion(SourceGateway gateway, IngestionRunRepository runs, RawPayloadStore payloads,
			CompleteIngestionRun completion, Clock clock, Duration pageDelay, int maxPages) {
		this.gateway = Objects.requireNonNull(gateway);
		this.runs = Objects.requireNonNull(runs);
		this.payloads = Objects.requireNonNull(payloads);
		this.completion = Objects.requireNonNull(completion);
		this.clock = Objects.requireNonNull(clock);
		this.pageDelay = Objects.requireNonNull(pageDelay);
		if (maxPages <= 0) {
			throw new IllegalArgumentException("maxPages must be positive");
		}
		this.maxPages = maxPages;
	}

	public IngestionRun run(IngestionJob job) {
		SourceDescriptor source = job.source();
		IngestionRun run = IngestionRun.start(IngestionRunId.newId(), source.dataset(), clock.instant());
		runs.save(run);
		log.info("ingestion {} started for {}", run.id(), job.name());
		try {
			int pageNumber = 0;
			int start = 0;
			while (true) {
				RawPage page = gateway.fetch(source, pageNumber, start);
				payloads.store(RawPayload.of(run.id(), source.dataset(), page));
				job.handle(page);
				run.pageFetched(page);
				log.debug("ingestion {} page {} records={} totalCount={} ms={}", run.id(), pageNumber,
						page.recordCount(), page.totalCount(), page.elapsed().toMillis());
				if (!hasMorePages(source, page, start)) {
					break;
				}
				pageNumber++;
				start += source.pagination().rows();
				if (pageNumber >= maxPages) {
					throw new IllegalStateException("more than " + maxPages + " pages for " + source.dataset()
							+ "; aborting to avoid an endless loop");
				}
				pause();
			}
			completion.succeed(run);
			log.info("ingestion {} succeeded for {}: {} records in {} pages", run.id(), job.name(), run.records(),
					run.pages());
		}
		catch (RuntimeException ex) {
			completion.fail(run, ex);
			log.warn("ingestion {} failed for {}: {}", run.id(), job.name(), run.error());
		}
		return run;
	}

	/**
	 * Reglas de S0.5: con {@code totalCount} se avanza mientras {@code start + registros < totalCount}; sin él, se
	 * avanza mientras la página venga llena (también en {@code PAGE}, datos.gob.es, S1.3). Una página vacía
	 * siempre termina.
	 */
	static boolean hasMorePages(SourceDescriptor source, RawPage page, int start) {
		if (source.pagination().mode() == Mode.NONE || page.recordCount() == 0) {
			return false;
		}
		int rows = source.pagination().rows();
		if (page.totalCount() != null) {
			return start + page.recordCount() < page.totalCount() && page.recordCount() >= rows;
		}
		return page.recordCount() >= rows;
	}

	private void pause() {
		if (pageDelay.isZero() || pageDelay.isNegative()) {
			return;
		}
		try {
			Thread.sleep(pageDelay.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while pacing requests", ex);
		}
	}

}
