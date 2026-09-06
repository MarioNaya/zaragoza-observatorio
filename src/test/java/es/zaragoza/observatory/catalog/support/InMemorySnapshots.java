package es.zaragoza.observatory.catalog.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import es.zaragoza.observatory.catalog.domain.FreshnessSnapshot;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;

/** Doble de {@link FreshnessSnapshotRepository}: una instantánea por dataset y día. */
public final class InMemorySnapshots implements FreshnessSnapshotRepository {

	public final List<FreshnessSnapshot> all = new ArrayList<>();

	@Override
	public void upsert(FreshnessSnapshot snapshot) {
		all.removeIf(s -> s.datasetSourceId() == snapshot.datasetSourceId()
				&& s.observedOn().equals(snapshot.observedOn()));
		all.add(snapshot);
	}

	@Override
	public Optional<FreshnessSnapshot> find(int datasetSourceId, LocalDate observedOn) {
		return all.stream()
				.filter(s -> s.datasetSourceId() == datasetSourceId && s.observedOn().equals(observedOn))
				.findFirst();
	}

	@Override
	public List<FreshnessSnapshot> history(int datasetSourceId, int limit) {
		return all.stream().filter(s -> s.datasetSourceId() == datasetSourceId)
				.sorted((a, b) -> b.observedOn().compareTo(a.observedOn())).limit(limit).toList();
	}

	@Override
	public Optional<FreshnessSnapshot> latest(int datasetSourceId) {
		return history(datasetSourceId, 1).stream().findFirst();
	}

}
