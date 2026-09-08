package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictRepository;
import jakarta.persistence.EntityManager;

/**
 * Adaptador de persistencia de las juntas. El alta y la actualización se hacen con SQL nativo porque el contorno
 * es una geometría de PostGIS que la entidad no mapea: se envía como GeoJSON y lo convierte
 * {@code ST_GeomFromGeoJSON} (V008). Las lecturas escalares van por JPA.
 * <p>
 * El upsert conserva {@code first_seen_at} y <b>no borra el {@code padron_id}</b>: el listado de juntas no lo
 * trae y llega después por el detalle de cada junta (S2.1).
 */
@Repository
class JpaDistrictRepository implements DistrictRepository {

	private static final String UPSERT = """
			INSERT INTO geo_district (id, padron_id, name, kind, boundary, first_seen_at, last_seen_at)
			VALUES (:id, NULL, :name, :kind, ST_SetSRID(ST_GeomFromGeoJSON(:boundary), 4326), :seenAt, :seenAt)
			ON CONFLICT (id) DO UPDATE SET
			    name = EXCLUDED.name,
			    kind = EXCLUDED.kind,
			    boundary = EXCLUDED.boundary,
			    last_seen_at = EXCLUDED.last_seen_at
			""";

	private final DistrictJpaRepository jpa;
	private final EntityManager entityManager;

	JpaDistrictRepository(DistrictJpaRepository jpa, EntityManager entityManager) {
		this.jpa = jpa;
		this.entityManager = entityManager;
	}

	@Override
	@Transactional
	public void upsert(District district, Instant seenAt) {
		if (district.boundary() == null) {
			throw new IllegalArgumentException("district " + district.id() + " has no boundary to store");
		}
		entityManager.createNativeQuery(UPSERT)
				.setParameter("id", district.id())
				.setParameter("name", district.name())
				.setParameter("kind", district.kind().name())
				.setParameter("boundary", GeoJson.of(district.boundary()))
				.setParameter("seenAt", seenAt)
				.executeUpdate();
	}

	@Override
	@Transactional
	public boolean updatePadronId(int districtId, int padronId) {
		var entity = jpa.findById(districtId).orElse(null);
		if (entity == null) {
			return false;
		}
		if (entity.getPadronId() != null && entity.getPadronId() == padronId) {
			return false;
		}
		entity.setPadronId(padronId);
		return true;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<District> findById(int id) {
		return jpa.findById(id).map(DistrictEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public List<District> findAll() {
		return jpa.findAllByOrderByIdAsc().stream().map(DistrictEntity::toDomain).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public long count() {
		return jpa.count();
	}

}
