package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DatasetJpaRepository extends JpaRepository<DatasetEntity, Integer>, JpaSpecificationExecutor<DatasetEntity> {

	List<DatasetEntity> findAllByOrderBySourceIdAsc();

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

	/** Nunca observadas primero, después las más antiguas; desempate por {@code sourceId}. */
	@Query("select d from DatasetEntity d where d.observedAt is null or d.observedAt < :before "
			+ "order by d.observedAt asc nulls first, d.sourceId asc")
	List<DatasetEntity> findDueForObservation(@Param("before") Instant before, Pageable pageable);

}
