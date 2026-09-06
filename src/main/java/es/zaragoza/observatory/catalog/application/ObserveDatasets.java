package es.zaragoza.observatory.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.DistributionObserver;
import es.zaragoza.observatory.catalog.domain.Observation;

/**
 * Muestreo de distribuciones (SPEC.md §3 fase 1, S1.1 recomendación 5): observa las fichas que llevan más de
 * {@code interval} sin observar (las nunca observadas primero), por lotes, y registra cada resultado en la
 * instantánea del día. Cada ficha se observa fuera de transacción; un fallo inesperado del adaptador se registra
 * como observación fallida para que la ficha no bloquee el lote indefinidamente.
 */
public class ObserveDatasets {

	private static final Logger log = LoggerFactory.getLogger(ObserveDatasets.class);

	private final DatasetRepository datasets;
	private final DistributionObserver observer;
	private final RecordObservation recordObservation;
	private final Clock clock;
	private final Duration interval;

	public ObserveDatasets(DatasetRepository datasets, DistributionObserver observer,
			RecordObservation recordObservation, Clock clock, Duration interval) {
		this.datasets = Objects.requireNonNull(datasets);
		this.observer = Objects.requireNonNull(observer);
		this.recordObservation = Objects.requireNonNull(recordObservation);
		this.clock = Objects.requireNonNull(clock);
		this.interval = Objects.requireNonNull(interval);
	}

	/** Observa hasta {@code limit} fichas vencidas y devuelve cuántas se observaron. */
	public int observeDue(int limit) {
		Instant now = clock.instant();
		List<Dataset> due = datasets.findDueForObservation(now.minus(interval), limit);
		int measured = 0;
		for (Dataset dataset : due) {
			if (observe(dataset).measured()) {
				measured++;
			}
		}
		if (!due.isEmpty()) {
			log.info("observed {} datasets ({} with a measure)", due.size(), measured);
		}
		return due.size();
	}

	public Observation observe(Dataset dataset) {
		Observation observation;
		try {
			observation = observer.observe(dataset);
		}
		catch (RuntimeException ex) {
			log.warn("unexpected error observing dataset {}", dataset.sourceId(), ex);
			observation = Observation.failed(null, null, clock.instant(), "error inesperado: " + ex);
		}
		recordObservation.record(dataset, observation);
		log.debug("dataset {} observed: {}", dataset.sourceId(), observation);
		return observation;
	}

}
