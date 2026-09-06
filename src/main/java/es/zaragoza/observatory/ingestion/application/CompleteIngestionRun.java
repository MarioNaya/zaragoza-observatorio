package es.zaragoza.observatory.ingestion.application;

import java.time.Clock;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.ingestion.domain.IngestionEventPublisher;
import es.zaragoza.observatory.ingestion.domain.IngestionRun;
import es.zaragoza.observatory.ingestion.domain.IngestionRunRepository;
import es.zaragoza.observatory.shared.DatasetIngested;

/**
 * Cierre transaccional de un run: el estado final y la publicación de {@code DatasetIngested} se confirman juntos,
 * de modo que el registro de eventos de Modulith (ADR-004) solo conserve eventos de runs realmente terminados.
 * Es un bean aparte de {@link RunIngestion} para que el proxy transaccional actúe (no hay autoinvocación).
 */
public class CompleteIngestionRun {

	private final IngestionRunRepository runs;
	private final IngestionEventPublisher events;
	private final Clock clock;

	public CompleteIngestionRun(IngestionRunRepository runs, IngestionEventPublisher events, Clock clock) {
		this.runs = Objects.requireNonNull(runs);
		this.events = Objects.requireNonNull(events);
		this.clock = Objects.requireNonNull(clock);
	}

	@Transactional
	public void succeed(IngestionRun run) {
		run.succeed(clock.instant());
		runs.save(run);
		events.publish(new DatasetIngested(run.dataset(), run.id(), run.records(), run.finishedAt()));
	}

	@Transactional
	public void fail(IngestionRun run, Throwable cause) {
		run.fail(clock.instant(), describe(cause));
		runs.save(run);
	}

	static String describe(Throwable cause) {
		String type = cause.getClass().getSimpleName();
		String message = cause.getMessage();
		String text = message == null || message.isBlank() ? type : type + ": " + message;
		return text.length() > 2000 ? text.substring(0, 2000) : text;
	}

}
