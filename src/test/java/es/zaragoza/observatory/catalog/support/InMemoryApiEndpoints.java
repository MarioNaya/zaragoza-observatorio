package es.zaragoza.observatory.catalog.support;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.catalog.domain.ApiEndpointRepository;

/** Doble de {@link ApiEndpointRepository} para tests unitarios (S1.2). */
public final class InMemoryApiEndpoints implements ApiEndpointRepository {

	public final Map<String, ApiEndpoint> byKey = new LinkedHashMap<>();

	@Override
	public int replaceAll(List<ApiEndpoint> endpoints, Instant seenAt) {
		Map<String, ApiEndpoint> next = new LinkedHashMap<>();
		for (ApiEndpoint endpoint : endpoints) {
			ApiEndpoint existing = byKey.get(endpoint.key());
			next.putIfAbsent(endpoint.key(),
					endpoint.seen(existing == null ? seenAt : existing.firstSeenAt(), seenAt));
		}
		byKey.clear();
		byKey.putAll(next);
		return byKey.size();
	}

	@Override
	public List<ApiEndpoint> findByTag(String tag) {
		return byKey.values().stream().filter(e -> e.tag().equals(tag))
				.sorted(Comparator.comparingInt(ApiEndpoint::ordinal)).toList();
	}

	@Override
	public long count() {
		return byKey.size();
	}

}
