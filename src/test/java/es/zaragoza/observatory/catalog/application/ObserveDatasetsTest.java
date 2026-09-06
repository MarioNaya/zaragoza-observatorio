package es.zaragoza.observatory.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.DistributionObserver;
import es.zaragoza.observatory.catalog.domain.FreshnessPolicy;
import es.zaragoza.observatory.catalog.domain.Observation;
import es.zaragoza.observatory.catalog.domain.ObservationMethod;
import es.zaragoza.observatory.catalog.domain.Periodicity;
import es.zaragoza.observatory.catalog.support.InMemoryDatasets;
import es.zaragoza.observatory.catalog.support.InMemorySnapshots;

/** Muestreo por lotes (S1.1 recomendación 5): orden de vencimiento, registro en la instantánea del día y errores. */
class ObserveDatasetsTest {

	static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");
	static final LocalDate TODAY = LocalDate.of(2026, 9, 6);
	static final Duration DAILY = Duration.ofDays(1);

	final InMemoryDatasets datasets = new InMemoryDatasets();
	final InMemorySnapshots snapshots = new InMemorySnapshots();
	final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	final List<Integer> observed = new ArrayList<>();
	final DistributionObserver observer = dataset -> {
		observed.add(dataset.sourceId());
		if (dataset.sourceId() % 2 == 0) {
			return Observation.failed(ObservationMethod.API_COUNT, "https://example/" + dataset.sourceId(), NOW,
					"HTTP 404 text/html");
		}
		return Observation.measured(ObservationMethod.FILE_HEADERS, "https://example/" + dataset.sourceId(), NOW,
				NOW.minus(Duration.ofDays(3)), null, "1 fichero(s) consultado(s), 1 con Last-Modified");
	};
	final RecordObservation recorder = new RecordObservation(datasets, snapshots, FreshnessPolicy.DEFAULT, clock);
	final ObserveDatasets useCase = new ObserveDatasets(datasets, observer, recorder, clock, DAILY);

	@Test
	void observesNeverObservedDatasetsFirstThenTheOldestAndSkipsRecentOnes() {
		datasets.upsert(dataset(1, "P1Y", TODAY.minusDays(30)), NOW);
		datasets.upsert(dataset(2, "NEVER", null), NOW);
		datasets.upsert(dataset(3, "P0DT1S", TODAY.minusDays(400)), NOW);
		datasets.observedAt.put(1, NOW.minus(Duration.ofDays(2)));
		datasets.observedAt.put(3, NOW.minus(Duration.ofHours(1)));

		int count = useCase.observeDue(10);

		assertThat(count).isEqualTo(2);
		assertThat(observed).containsExactly(2, 1);
		assertThat(datasets.observedAt).containsEntry(1, NOW).containsEntry(2, NOW);
		assertThat(snapshots.find(1, TODAY)).get().satisfies(s -> {
			assertThat(s.declared()).isEqualTo(DeclaredFreshness.ON_TIME);
			assertThat(s.observationMethod()).isEqualTo(ObservationMethod.FILE_HEADERS);
			assertThat(s.observedLastChange()).isEqualTo(NOW.minus(Duration.ofDays(3)));
			assertThat(s.observationError()).isNull();
		});
		assertThat(snapshots.find(2, TODAY)).get().satisfies(s -> {
			assertThat(s.declared()).isEqualTo(DeclaredFreshness.NOT_EVALUABLE);
			assertThat(s.observationMethod()).isEqualTo(ObservationMethod.API_COUNT);
			assertThat(s.observedLastChange()).isNull();
			assertThat(s.observationError()).isEqualTo("HTTP 404 text/html");
		});
		assertThat(datasets.latestFreshness).containsEntry(1, DeclaredFreshness.ON_TIME);
		assertThat(snapshots.find(3, TODAY)).isEmpty();
	}

	@Test
	void limitAppliesAfterOrdering() {
		datasets.upsert(dataset(5, "P1Y", TODAY.minusDays(30)), NOW);
		datasets.upsert(dataset(7, "P1Y", TODAY.minusDays(30)), NOW);
		datasets.upsert(dataset(9, "P1Y", TODAY.minusDays(30)), NOW);
		datasets.observedAt.put(5, NOW.minus(Duration.ofDays(5)));

		useCase.observeDue(2);

		assertThat(observed).containsExactly(7, 9);
	}

	@Test
	void observationWrittenTwiceTheSameDayReplacesTheObservedAxisOnly() {
		Dataset dataset = dataset(1, "P1Y", TODAY.minusDays(30));
		datasets.upsert(dataset, NOW);

		useCase.observe(dataset);
		recorder.record(dataset, Observation.measured(ObservationMethod.WFS_HITS, "https://example/wfs", NOW, null,
				3359, "typeNames=Vias"));

		assertThat(snapshots.history(1, 10)).singleElement().satisfies(s -> {
			assertThat(s.declared()).isEqualTo(DeclaredFreshness.ON_TIME);
			assertThat(s.observationMethod()).isEqualTo(ObservationMethod.WFS_HITS);
			assertThat(s.observedRecords()).isEqualTo(3359);
			assertThat(s.observedLastChange()).isNull();
		});
		assertThat(datasets.latestObservation.get(1).method()).isEqualTo(ObservationMethod.WFS_HITS);
	}

	@Test
	void unexpectedObserverFailureIsRecordedAsFailedObservation() {
		Dataset dataset = dataset(1, "P1Y", TODAY.minusDays(30));
		datasets.upsert(dataset, NOW);
		DistributionObserver broken = d -> {
			throw new IllegalStateException("boom");
		};
		var useCaseWithBrokenObserver = new ObserveDatasets(datasets, broken, recorder, clock, DAILY);

		Observation observation = useCaseWithBrokenObserver.observe(dataset);

		assertThat(observation.failed()).isTrue();
		assertThat(observation.method()).isNull();
		assertThat(observation.error()).contains("boom");
		assertThat(datasets.observedAt).containsEntry(1, NOW);
		assertThat(snapshots.find(1, TODAY)).get().extracting(s -> s.observationError()).asString().contains("boom");
	}

	static Dataset dataset(int id, String periodicity, LocalDate modified) {
		return new Dataset(id, "dataset " + id, null, null, modified == null ? null : modified.atStartOfDay(),
				LocalDateTime.of(2026, 1, 20, 13, 12, 38), periodicity, Periodicity.days(periodicity), "Finalizado",
				true, true, false, null, List.of(), NOW, NOW);
	}

}
