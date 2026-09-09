package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import es.zaragoza.observatory.geo.infrastructure.persistence.PopulationRecordEntity.Key;

interface PopulationRecordJpaRepository extends JpaRepository<PopulationRecordEntity, Key> {

	List<PopulationRecordEntity> findByDistrictIdOrderByYearDesc(Integer districtId);

	List<PopulationRecordEntity> findAllByOrderByDistrictIdAscYearDesc();

	Optional<PopulationRecordEntity> findFirstByDistrictIdOrderByYearDesc(Integer districtId);

	@Query("select distinct p.year from PopulationRecordEntity p order by p.year desc")
	List<Integer> years();

}
