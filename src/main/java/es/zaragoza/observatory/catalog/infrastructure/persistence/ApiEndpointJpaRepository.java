package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

interface ApiEndpointJpaRepository
		extends JpaRepository<ApiEndpointEntity, UUID>, JpaSpecificationExecutor<ApiEndpointEntity> {

	List<ApiEndpointEntity> findByTagOrderByOrdinalAsc(String tag);

	@Query("select e.tag, count(e) from ApiEndpointEntity e group by e.tag order by e.tag asc")
	List<Object[]> countByTag();

	@Query("select count(distinct e.tag) from ApiEndpointEntity e")
	long countDistinctTags();

	/** Tags documentados que ninguna ficha del catálogo declara. */
	@Query("select count(distinct e.tag) from ApiEndpointEntity e "
			+ "where e.tag not in (select d.apiTag from DatasetEntity d where d.apiTag is not null)")
	long countTagsWithoutDataset();

}
