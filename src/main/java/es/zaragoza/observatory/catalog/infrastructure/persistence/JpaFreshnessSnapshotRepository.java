package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.FreshnessSnapshot;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;

@Repository
class JpaFreshnessSnapshotRepository implements FreshnessSnapshotRepository {

	private final FreshnessSnapshotJpaRepository jpa;

	JpaFreshnessSnapshotRepository(FreshnessSnapshotJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional
	public void upsert(FreshnessSnapshot snapshot) {
		jpa.findByDatasetSourceIdAndObservedOn(snapshot.datasetSourceId(), snapshot.observedOn())
				.ifPresentOrElse(existing -> existing.apply(snapshot),
						() -> jpa.save(FreshnessSnapshotEntity.from(snapshot)));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<FreshnessSnapshot> find(int datasetSourceId, LocalDate observedOn) {
		return jpa.findByDatasetSourceIdAndObservedOn(datasetSourceId, observedOn)
				.map(FreshnessSnapshotEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public List<FreshnessSnapshot> history(int datasetSourceId, int limit) {
		return jpa.findByDatasetSourceIdOrderByObservedOnDesc(datasetSourceId, PageRequest.of(0, Math.max(1, limit)))
				.stream().map(FreshnessSnapshotEntity::toDomain).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<FreshnessSnapshot> latest(int datasetSourceId) {
		return jpa.findFirstByDatasetSourceIdOrderByObservedOnDesc(datasetSourceId)
				.map(FreshnessSnapshotEntity::toDomain);
	}

}
