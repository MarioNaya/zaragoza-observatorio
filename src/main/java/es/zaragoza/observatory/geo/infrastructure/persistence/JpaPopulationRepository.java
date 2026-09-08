package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.geo.domain.PopulationRecord;
import es.zaragoza.observatory.geo.domain.PopulationRepository;
import es.zaragoza.observatory.geo.infrastructure.persistence.PopulationRecordEntity.Key;

@Repository
class JpaPopulationRepository implements PopulationRepository {

	private final PopulationRecordJpaRepository jpa;

	JpaPopulationRepository(PopulationRecordJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional
	public void upsert(PopulationRecord record) {
		jpa.findById(new Key(record.districtId(), record.year())).ifPresentOrElse(
				existing -> existing.apply(record), () -> jpa.save(PopulationRecordEntity.insert(record)));
	}

	@Override
	@Transactional(readOnly = true)
	public List<PopulationRecord> findByDistrict(int districtId) {
		return jpa.findByDistrictIdOrderByYearDesc(districtId).stream().map(PopulationRecordEntity::toDomain).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PopulationRecord> findLatest(int districtId) {
		return jpa.findFirstByDistrictIdOrderByYearDesc(districtId).map(PopulationRecordEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Integer> years() {
		return jpa.years();
	}

	@Override
	@Transactional(readOnly = true)
	public long count() {
		return jpa.count();
	}

}
