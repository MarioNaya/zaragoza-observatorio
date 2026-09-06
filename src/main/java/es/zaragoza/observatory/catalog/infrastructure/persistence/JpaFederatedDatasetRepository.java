package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.FederatedDataset;
import es.zaragoza.observatory.catalog.domain.FederatedDatasetRepository;

@Repository
class JpaFederatedDatasetRepository implements FederatedDatasetRepository {

	private final FederatedDatasetJpaRepository jpa;

	JpaFederatedDatasetRepository(FederatedDatasetJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional
	public void upsert(FederatedDataset dataset, Instant seenAt) {
		jpa.findById(dataset.sourceId()).ifPresentOrElse(existing -> existing.apply(dataset, seenAt),
				() -> jpa.save(FederatedDatasetEntity.insert(dataset, seenAt)));
	}

	@Override
	@Transactional
	public int deleteNotSeenSince(Instant since) {
		return jpa.deleteNotSeenSince(since);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<FederatedDataset> findBySourceId(int sourceId) {
		return jpa.findById(sourceId).map(FederatedDatasetEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public long count() {
		return jpa.count();
	}

}
