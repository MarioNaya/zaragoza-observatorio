package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface FreshnessSnapshotJpaRepository extends JpaRepository<FreshnessSnapshotEntity, UUID> {

	Optional<FreshnessSnapshotEntity> findByDatasetSourceIdAndObservedOn(int datasetSourceId, LocalDate observedOn);

	List<FreshnessSnapshotEntity> findByDatasetSourceIdOrderByObservedOnDesc(int datasetSourceId, Pageable pageable);

	Optional<FreshnessSnapshotEntity> findFirstByDatasetSourceIdOrderByObservedOnDesc(int datasetSourceId);

}
