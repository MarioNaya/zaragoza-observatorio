package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.catalog.domain.ApiEndpointRepository;

@Repository
class JpaApiEndpointRepository implements ApiEndpointRepository {

	private final ApiEndpointJpaRepository jpa;

	JpaApiEndpointRepository(ApiEndpointJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional
	public int replaceAll(List<ApiEndpoint> endpoints, Instant seenAt) {
		Map<String, ApiEndpointEntity> existing = new HashMap<>();
		jpa.findAll().forEach(entity -> existing.put(entity.key(), entity));
		List<ApiEndpointEntity> inserts = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (ApiEndpoint endpoint : endpoints) {
			if (!seen.add(endpoint.key())) {
				continue; // clave repetida dentro del documento: se conserva la primera
			}
			ApiEndpointEntity entity = existing.remove(endpoint.key());
			if (entity == null) {
				inserts.add(ApiEndpointEntity.insert(endpoint, seenAt));
			}
			else {
				entity.apply(endpoint, seenAt);
			}
		}
		jpa.saveAll(inserts);
		jpa.deleteAll(existing.values());
		return seen.size();
	}

	@Override
	@Transactional(readOnly = true)
	public List<ApiEndpoint> findByTag(String tag) {
		return jpa.findByTagOrderByOrdinalAsc(tag).stream().map(ApiEndpointEntity::toDomain).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public long count() {
		return jpa.count();
	}

}
