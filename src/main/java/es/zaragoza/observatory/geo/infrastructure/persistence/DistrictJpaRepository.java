package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface DistrictJpaRepository extends JpaRepository<DistrictEntity, Integer> {

	List<DistrictEntity> findAllByOrderByIdAsc();

}
