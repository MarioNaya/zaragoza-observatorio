package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FederatedDatasetJpaRepository
		extends JpaRepository<FederatedDatasetEntity, Integer>, JpaSpecificationExecutor<FederatedDatasetEntity> {

	@Modifying
	@Query("delete from FederatedDatasetEntity f where f.lastSeenAt < :since")
	int deleteNotSeenSince(@Param("since") Instant since);

	/** Federados con ficha en el catálogo ingerido. */
	@Query("select count(f) from FederatedDatasetEntity f where f.sourceId in (select d.sourceId from DatasetEntity d)")
	long countInCatalog();

}
