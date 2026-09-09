package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.DatasetRepository.Delisting;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.Observation;

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
	@Transactional
	public Delisting markNotSeenSince(Instant runStartedAt) {
		// El orden importa: primero se desmarca lo que ha vuelto, y solo entonces se marca lo que falta, para que
		// una ficha no pueda quedar marcada y desmarcada en la misma pasada.
		int relisted = jpa.clearDelistedSeenSince(runStartedAt);
		int delisted = jpa.markNotSeenSince(runStartedAt);
		return new Delisting(delisted, relisted);
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

	@Override
	@Transactional(readOnly = true)
	public List<Dataset> findDueForObservation(Instant observedBefore, int limit) {
		return jpa.findDueForObservation(observedBefore, PageRequest.of(0, Math.max(1, limit))).stream()
				.map(DatasetEntity::toDomain).toList();
	}

	@Override
	@Transactional
	public void recordObservation(int sourceId, Observation observation) {
		jpa.findById(sourceId).ifPresent(entity -> entity.recordObservation(observation));
	}

}
