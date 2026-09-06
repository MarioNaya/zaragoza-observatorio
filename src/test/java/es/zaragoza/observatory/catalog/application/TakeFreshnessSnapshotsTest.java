package es.zaragoza.observatory.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.FreshnessPolicy;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshot;
import es.zaragoza.observatory.catalog.domain.Observation;
import es.zaragoza.observatory.catalog.domain.ObservationMethod;
import es.zaragoza.observatory.catalog.domain.Periodicity;
import es.zaragoza.observatory.catalog.support.InMemoryDatasets;
import es.zaragoza.observatory.catalog.support.InMemorySnapshots;

class TakeFreshnessSnapshotsTest {

	static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");
	static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

	final InMemoryDatasets datasets = new InMemoryDatasets();
	final InMemorySnapshots snapshots = new InMemorySnapshots();
	final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	final TakeFreshnessSnapshots useCase = new TakeFreshnessSnapshots(datasets, snapshots, FreshnessPolicy.DEFAULT,
			clock);

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
			assertThat(s.hasObservation()).isFalse();
			assertThat(s.observedLastChange()).isNull();
		});
		assertThat(snapshots.latest(2)).get().extracting(FreshnessSnapshot::declared)
				.isEqualTo(DeclaredFreshness.NOT_UPDATED);
		assertThat(snapshots.latest(3)).get().extracting(FreshnessSnapshot::declared)
				.isEqualTo(DeclaredFreshness.NOT_EVALUABLE);
		assertThat(datasets.latestFreshness).containsEntry(1, DeclaredFreshness.ON_TIME)
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

	@Test
	void recalculatingTheDeclaredAxisKeepsTheObservationOfTheDay() {
		Dataset dataset = dataset(1, "P1Y", TODAY.minusDays(100));
		datasets.upsert(dataset, NOW);
		var recorder = new RecordObservation(datasets, snapshots, FreshnessPolicy.DEFAULT, clock);
		var observation = Observation.measured(ObservationMethod.API_MAX_DATE, "https://example/x.json", NOW,
				NOW.minusSeconds(3600), 42, "lastUpdated");
		recorder.record(dataset, observation);

		useCase.take(TODAY);

		assertThat(snapshots.find(1, TODAY)).get().satisfies(s -> {
			assertThat(s.declared()).isEqualTo(DeclaredFreshness.ON_TIME);
			assertThat(s.observationMethod()).isEqualTo(ObservationMethod.API_MAX_DATE);
			assertThat(s.observedLastChange()).isEqualTo(NOW.minusSeconds(3600));
			assertThat(s.observedRecords()).isEqualTo(42);
			assertThat(s.observationDetail()).isEqualTo("lastUpdated");
		});
		assertThat(snapshots.history(1, 10)).hasSize(1);
	}

	static Dataset dataset(int id, String periodicity, LocalDate modified) {
		return new Dataset(id, "dataset " + id, null, null, modified.atStartOfDay(),
				LocalDateTime.of(2026, 1, 20, 13, 12, 38), periodicity, Periodicity.days(periodicity), "Finalizado",
				true, true, false, null, List.of(), NOW, NOW);
	}

}
