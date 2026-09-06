package es.zaragoza.observatory.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.FreshnessPolicy;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshot;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;
import es.zaragoza.observatory.catalog.domain.Periodicity;

class TakeFreshnessSnapshotsTest {

	static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");
	static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

	final InMemoryDatasets datasets = new InMemoryDatasets();
	final InMemorySnapshots snapshots = new InMemorySnapshots();
	final TakeFreshnessSnapshots useCase = new TakeFreshnessSnapshots(datasets, snapshots, FreshnessPolicy.DEFAULT,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void takesOneDeclaredSnapshotPerDatasetAndRecordsLatestFreshness() {
		datasets.upsert(dataset(1, "P1Y", TODAY.minusDays(100)), NOW);
		datasets.upsert(dataset(2, "P1M", LocalDate.of(2008, 1, 1)), NOW);
		datasets.upsert(dataset(3, "NEVER", LocalDate.of(2007, 1, 1)), NOW);

		int taken = useCase.take(TODAY);

		assertThat(taken).isEqualTo(3);
		assertThat(snapshots.latest(1)).get().satisfies(s -> {
			assertThat(s.declared()).isEqualTo(DeclaredFreshness.ON_TIME);
			assertThat(s.observedOn()).isEqualTo(TODAY);
			assertThat(s.takenAt()).isEqualTo(NOW);
			assertThat(s.observedLastChange()).isNull();
		});
		assertThat(snapshots.latest(2)).get().extracting(FreshnessSnapshot::declared)
				.isEqualTo(DeclaredFreshness.NOT_UPDATED);
		assertThat(snapshots.latest(3)).get().extracting(FreshnessSnapshot::declared)
				.isEqualTo(DeclaredFreshness.NOT_EVALUABLE);
		assertThat(datasets.latest).containsEntry(1, DeclaredFreshness.ON_TIME)
				.containsEntry(2, DeclaredFreshness.NOT_UPDATED).containsEntry(3, DeclaredFreshness.NOT_EVALUABLE);
	}

	@Test
	void repeatingTheSameDayReplacesInsteadOfDuplicating() {
		datasets.upsert(dataset(1, "P1Y", TODAY.minusDays(100)), NOW);

		useCase.take(TODAY);
		useCase.take(TODAY);
		useCase.take(TODAY.plusDays(1));

		assertThat(snapshots.history(1, 10)).extracting(FreshnessSnapshot::observedOn)
				.containsExactly(TODAY.plusDays(1), TODAY);
	}

	static Dataset dataset(int id, String periodicity, LocalDate modified) {
		return new Dataset(id, "dataset " + id, null, null, modified.atStartOfDay(),
				LocalDateTime.of(2026, 1, 20, 13, 12, 38), periodicity, Periodicity.days(periodicity), "Finalizado",
				true, true, false, null, List.of(), NOW, NOW);
	}

	static final class InMemoryDatasets implements DatasetRepository {
		final Map<Integer, Dataset> byId = new LinkedHashMap<>();
		final Map<Integer, DeclaredFreshness> latest = new LinkedHashMap<>();

		@Override
		public void upsert(Dataset dataset, Instant seenAt) {
			byId.put(dataset.sourceId(), dataset);
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
		public void recordLatestFreshness(int sourceId, DeclaredFreshness freshness, Double ratio,
				LocalDate observedOn) {
			latest.put(sourceId, freshness);
		}
	}

	static final class InMemorySnapshots implements FreshnessSnapshotRepository {
		final List<FreshnessSnapshot> all = new ArrayList<>();

		@Override
		public void upsert(FreshnessSnapshot snapshot) {
			all.removeIf(s -> s.datasetSourceId() == snapshot.datasetSourceId()
					&& s.observedOn().equals(snapshot.observedOn()));
			all.add(snapshot);
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

}
