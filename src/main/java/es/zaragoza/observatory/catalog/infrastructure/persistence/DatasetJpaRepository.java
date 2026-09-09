package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DatasetJpaRepository extends JpaRepository<DatasetEntity, Integer>, JpaSpecificationExecutor<DatasetEntity> {

	List<DatasetEntity> findAllByOrderBySourceIdAsc();

	/** ADR-013: no vistas en la ejecución iniciada en {@code since} y aún sin marcar. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update DatasetEntity d set d.delistedAt = :since where d.lastSeenAt < :since and d.delistedAt is null")
	int markNotSeenSince(@Param("since") Instant since);

	/** ADR-013: marcadas que han vuelto a aparecer en la ejecución iniciada en {@code since}. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update DatasetEntity d set d.delistedAt = null where d.lastSeenAt >= :since and d.delistedAt is not null")
	int clearDelistedSeenSince(@Param("since") Instant since);

	long countByDelistedAtIsNotNull();

	@Query("select d.latestFreshness, count(d) from DatasetEntity d group by d.latestFreshness")
	List<Object[]> countByLatestFreshness();

	@Query("select d.declaredPeriodicity, count(d) from DatasetEntity d group by d.declaredPeriodicity")
	List<Object[]> countByDeclaredPeriodicity();

	@Query("select d.latestObservationMethod, count(d) from DatasetEntity d group by d.latestObservationMethod")
	List<Object[]> countByLatestObservationMethod();

	long countByHasApiTrue();

	long countByOpenTrue();

	long countByExplorableTrue();

	long countByHasGeoTrue();

	@Query("select max(d.latestSnapshotOn) from DatasetEntity d")
	LocalDate latestSnapshotOn();

	/** Fichas que declaran un tag del Swagger: {@code sourceId}, {@code title}, {@code apiTag} (S1.2). */
	@Query("select d.sourceId, d.title, d.apiTag from DatasetEntity d where d.apiTag is not null "
			+ "order by d.apiTag asc, d.sourceId asc")
	List<Object[]> findApiTagged();

	long countByApiTagIsNotNull();

	/** Fichas cuyo tag existe en el inventario de endpoints. */
	@Query("select count(d) from DatasetEntity d where d.apiTag in (select distinct e.tag from ApiEndpointEntity e)")
	long countWithDocumentedApiTag();

	/** Fichas presentes en datos.gob.es (S1.3). */
	@Query("select count(d) from DatasetEntity d where d.sourceId in (select f.sourceId from FederatedDatasetEntity f)")
	long countFederated();

	/** Nunca observadas primero, después las más antiguas; desempate por {@code sourceId}. */
	@Query("select d from DatasetEntity d where d.observedAt is null or d.observedAt < :before "
			+ "order by d.observedAt asc nulls first, d.sourceId asc")
	List<DatasetEntity> findDueForObservation(@Param("before") Instant before, Pageable pageable);

}
