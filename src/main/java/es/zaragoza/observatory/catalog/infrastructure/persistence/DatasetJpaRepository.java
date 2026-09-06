package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

interface DatasetJpaRepository extends JpaRepository<DatasetEntity, Integer>, JpaSpecificationExecutor<DatasetEntity> {

	List<DatasetEntity> findAllByOrderBySourceIdAsc();

	@Query("select d.latestFreshness, count(d) from DatasetEntity d group by d.latestFreshness")
	List<Object[]> countByLatestFreshness();

	@Query("select d.declaredPeriodicity, count(d) from DatasetEntity d group by d.declaredPeriodicity")
	List<Object[]> countByDeclaredPeriodicity();

	long countByHasApiTrue();

	long countByOpenTrue();

	long countByExplorableTrue();

	long countByHasGeoTrue();

	@Query("select max(d.latestSnapshotOn) from DatasetEntity d")
	LocalDate latestSnapshotOn();

}
