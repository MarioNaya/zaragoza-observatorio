package es.zaragoza.observatory.catalog.support;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.Observation;

/** Doble de {@link DatasetRepository} para tests unitarios de los casos de uso. */
public final class InMemoryDatasets implements DatasetRepository {

	public final Map<Integer, Dataset> byId = new LinkedHashMap<>();
	public final Map<Integer, DeclaredFreshness> latestFreshness = new LinkedHashMap<>();
	public final Map<Integer, Instant> observedAt = new LinkedHashMap<>();
	public final Map<Integer, Observation> latestObservation = new LinkedHashMap<>();
	public final Map<Integer, Instant> delistedAt = new LinkedHashMap<>();

	@Override
	public void upsert(Dataset dataset, Instant seenAt) {
		byId.put(dataset.sourceId(), dataset);
	}

	@Override
	public Delisting markNotSeenSince(Instant runStartedAt) {
		int relisted = 0;
		int delisted = 0;
		for (Dataset dataset : byId.values()) {
			boolean seen = !dataset.lastSeenAt().isBefore(runStartedAt);
			if (seen && delistedAt.remove(dataset.sourceId()) != null) {
				relisted++;
			}
			else if (!seen && !delistedAt.containsKey(dataset.sourceId())) {
				delistedAt.put(dataset.sourceId(), runStartedAt);
				delisted++;
			}
		}
		return new Delisting(delisted, relisted);
	}

	@Override
	public Optional<Dataset> findBySourceId(int sourceId) {
		return Optional.ofNullable(byId.get(sourceId));
	}

	@Override
	public List<Dataset> findAll() {
		return new ArrayList<>(byId.values());
	}

	@Override
	public long count() {
		return byId.size();
	}

	@Override
	public void recordLatestFreshness(int sourceId, DeclaredFreshness freshness, Double ratio, LocalDate observedOn) {
		latestFreshness.put(sourceId, freshness);
	}

	@Override
	public List<Dataset> findDueForObservation(Instant observedBefore, int limit) {
		Comparator<Dataset> nullsFirstThenOldest = Comparator.comparing(
				(Dataset d) -> observedAt.get(d.sourceId()), Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparing(Dataset::sourceId);
		return byId.values().stream()
				.filter(d -> observedAt.get(d.sourceId()) == null || observedAt.get(d.sourceId()).isBefore(observedBefore))
				.sorted(nullsFirstThenOldest)
				.limit(limit)
				.toList();
	}

	@Override
	public void recordObservation(int sourceId, Observation observation) {
		observedAt.put(sourceId, observation.observedAt());
		latestObservation.put(sourceId, observation);
	}

}
