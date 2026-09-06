package es.zaragoza.observatory.ingestion.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import es.zaragoza.observatory.ingestion.RunStatus;

interface IngestionRunJpaRepository extends JpaRepository<IngestionRunEntity, UUID> {

	Optional<IngestionRunEntity> findFirstBySourceAndDatasetIdAndStatusOrderByStartedAtDesc(String source,
			String datasetId, RunStatus status);

	List<IngestionRunEntity> findBySourceAndDatasetIdOrderByStartedAtDesc(String source, String datasetId,
			Pageable pageable);

}
