package es.zaragoza.observatory.ingestion.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.ingestion.domain.IngestionRun;
import es.zaragoza.observatory.ingestion.domain.IngestionRunRepository;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;

@Repository
class JpaIngestionRunRepository implements IngestionRunRepository {

	private final IngestionRunJpaRepository jpa;

	JpaIngestionRunRepository(IngestionRunJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional
	public void save(IngestionRun run) {
		jpa.save(IngestionRunEntity.from(run));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<IngestionRun> find(IngestionRunId id) {
		return jpa.findById(id.value()).map(IngestionRunEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<IngestionRun> lastSucceeded(DatasetRef dataset) {
		return jpa.findFirstBySourceAndDatasetIdAndStatusOrderByStartedAtDesc(dataset.source(), dataset.id(),
				RunStatus.SUCCEEDED).map(IngestionRunEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public List<IngestionRun> history(DatasetRef dataset, int limit) {
		return jpa.findBySourceAndDatasetIdOrderByStartedAtDesc(dataset.source(), dataset.id(),
				PageRequest.of(0, Math.max(1, limit))).stream().map(IngestionRunEntity::toDomain).toList();
	}

}
