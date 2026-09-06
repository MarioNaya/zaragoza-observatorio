package es.zaragoza.observatory.catalog.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.FreshnessPolicy;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshot;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;

/**
 * Calcula la instantánea de frescura declarada de todas las fichas para un día (SPEC.md §4.6). Idempotente: una
 * instantánea por dataset y día; repetirla el mismo día la sustituye.
 */
public class TakeFreshnessSnapshots {

	private static final Logger log = LoggerFactory.getLogger(TakeFreshnessSnapshots.class);

	private final DatasetRepository datasets;
	private final FreshnessSnapshotRepository snapshots;
	private final FreshnessPolicy policy;
	private final Clock clock;

	public TakeFreshnessSnapshots(DatasetRepository datasets, FreshnessSnapshotRepository snapshots,
			FreshnessPolicy policy, Clock clock) {
		this.datasets = Objects.requireNonNull(datasets);
		this.snapshots = Objects.requireNonNull(snapshots);
		this.policy = Objects.requireNonNull(policy);
		this.clock = Objects.requireNonNull(clock);
	}

	@Transactional
	public int take(LocalDate observedOn) {
		int taken = 0;
		for (Dataset dataset : datasets.findAll()) {
			var evaluation = policy.evaluate(dataset, observedOn);
			var snapshot = FreshnessSnapshot.declaredOnly(dataset, observedOn, clock.instant(), evaluation);
			snapshots.upsert(snapshot);
			datasets.recordLatestFreshness(dataset.sourceId(), snapshot.declared(), snapshot.declaredRatio(),
					observedOn);
			taken++;
		}
		log.info("freshness snapshots taken for {} datasets on {}", taken, observedOn);
		return taken;
	}

}
