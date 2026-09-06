package es.zaragoza.observatory.catalog.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.FreshnessPolicy;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshot;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;
import es.zaragoza.observatory.catalog.domain.Observation;
import es.zaragoza.observatory.shared.ZaragozaTime;

/**
 * Escribe una observación en la instantánea del día (SPEC.md §4.6, S1.1): si el día ya tiene instantánea
 * declarada, le añade el eje observado; si no (la observación llegó antes que el cálculo declarado), crea la
 * instantánea con ambos ejes. Idempotente: repetir el mismo día sustituye la observación. Separado de
 * {@link ObserveDatasets} para que la transacción no abarque las peticiones HTTP.
 */
public class RecordObservation {

	private final DatasetRepository datasets;
	private final FreshnessSnapshotRepository snapshots;
	private final FreshnessPolicy policy;
	private final Clock clock;

	public RecordObservation(DatasetRepository datasets, FreshnessSnapshotRepository snapshots,
			FreshnessPolicy policy, Clock clock) {
		this.datasets = Objects.requireNonNull(datasets);
		this.snapshots = Objects.requireNonNull(snapshots);
		this.policy = Objects.requireNonNull(policy);
		this.clock = Objects.requireNonNull(clock);
	}

	@Transactional
	public FreshnessSnapshot record(Dataset dataset, Observation observation) {
		LocalDate day = LocalDate.ofInstant(observation.observedAt(), ZaragozaTime.ZONE);
		FreshnessSnapshot snapshot = snapshots.find(dataset.sourceId(), day).orElseGet(() -> {
			var created = FreshnessSnapshot.declaredOnly(dataset, day, clock.instant(), policy.evaluate(dataset, day));
			datasets.recordLatestFreshness(dataset.sourceId(), created.declared(), created.declaredRatio(), day);
			return created;
		});
		FreshnessSnapshot updated = snapshot.withObservation(observation);
		snapshots.upsert(updated);
		datasets.recordObservation(dataset.sourceId(), observation);
		return updated;
	}

}
