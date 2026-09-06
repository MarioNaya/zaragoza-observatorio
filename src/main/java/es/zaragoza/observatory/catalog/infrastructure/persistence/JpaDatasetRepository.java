package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;

@Repository
class JpaDatasetRepository implements DatasetRepository {

	private final DatasetJpaRepository jpa;

	JpaDatasetRepository(DatasetJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional
	public void upsert(Dataset dataset, Instant seenAt) {
		jpa.findById(dataset.sourceId()).ifPresentOrElse(existing -> existing.apply(dataset, seenAt),
				() -> jpa.save(DatasetEntity.insert(dataset, seenAt)));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Dataset> findBySourceId(int sourceId) {
		return jpa.findById(sourceId).map(DatasetEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Dataset> findAll() {
		return jpa.findAllByOrderBySourceIdAsc().stream().map(DatasetEntity::toDomain).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public long count() {
		return jpa.count();
	}

	@Override
	@Transactional
	public void recordLatestFreshness(int sourceId, DeclaredFreshness freshness, Double ratio, LocalDate observedOn) {
		jpa.findById(sourceId).ifPresent(entity -> entity.recordLatestFreshness(freshness, ratio, observedOn));
	}

}
