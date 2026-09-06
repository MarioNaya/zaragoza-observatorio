package es.zaragoza.observatory.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.ingestion.domain.IngestionEventPublisher;
import es.zaragoza.observatory.ingestion.domain.IngestionRun;
import es.zaragoza.observatory.ingestion.domain.IngestionRunRepository;
import es.zaragoza.observatory.ingestion.domain.RawPayload;
import es.zaragoza.observatory.ingestion.domain.RawPayloadStore;
import es.zaragoza.observatory.ingestion.domain.SourceAccessException;
import es.zaragoza.observatory.ingestion.domain.SourceAccessException.Kind;
import es.zaragoza.observatory.ingestion.domain.SourceGateway;
import es.zaragoza.observatory.shared.DatasetIngested;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;
import es.zaragoza.observatory.shared.Sources;

/** Caso de uso con puertos falsos: paginación según S0.5, cierre del run y publicación del evento. */
class RunIngestionTest {

	static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");
	static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	static final DatasetRef DATASET = DatasetRef.of(Sources.SEDE, "presupuesto/gasto-corriente");
	static final URI URL = URI.create("https://www.zaragoza.es/sede/servicio/presupuesto/gasto-corriente.json");

	final InMemoryRuns runs = new InMemoryRuns();
	final InMemoryPayloads payloads = new InMemoryPayloads();
	final List<DatasetIngested> events = new ArrayList<>();
	final List<RawPage> handled = new ArrayList<>();

	@Test
	void singleRequestWhenPaginationIsNone() {
		var gateway = pages(start -> page(0, start, 5720, null));
		var run = runIngestion(gateway).run(job(Pagination.none(500), ResponseShape.ARRAY));

		assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(run.pages()).isEqualTo(1);
		assertThat(run.records()).isEqualTo(5720);
		assertThat(run.finishedAt()).isEqualTo(NOW);
		assertThat(gateway.starts).containsExactly(0);
		assertThat(handled).hasSize(1);
		assertThat(payloads.stored).hasSize(1);
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.dataset()).isEqualTo(DATASET);
			assertThat(event.run()).isEqualTo(run.id());
			assertThat(event.records()).isEqualTo(5720);
			assertThat(event.ingestedAt()).isEqualTo(NOW);
		});
		assertThat(runs.lastSucceeded(DATASET)).map(IngestionRun::id).contains(run.id());
	}

	@Test
	void offsetPaginationStopsWhenTotalCountIsReached() {
		// presupuesto: totalCount 1.251 con rows=500 (S0.5) -> 500, 500, 251
		var gateway = pages(start -> page(start / 500, start, Math.min(500, 1251 - start), 1251));
		var run = runIngestion(gateway).run(job(Pagination.offset(500), ResponseShape.ENVELOPE));

		assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(gateway.starts).containsExactly(0, 500, 1000);
		assertThat(run.pages()).isEqualTo(3);
		assertThat(run.records()).isEqualTo(1251);
		assertThat(payloads.stored).extracting(RawPayload::pageNumber).containsExactly(0, 1, 2);
	}

	@Test
	void offsetPaginationStopsAtFirstShortPageWhenTotalCountIsMissing() {
		// quejas de sede: array sin totalCount (S0.5) -> 500, 500, 120
		var gateway = pages(start -> page(start / 500, start, start < 1000 ? 500 : 120, null));
		var run = runIngestion(gateway).run(job(Pagination.offset(500), ResponseShape.ARRAY));

		assertThat(gateway.starts).containsExactly(0, 500, 1000);
		assertThat(run.records()).isEqualTo(1120);
	}

	@Test
	void emptyFirstPageSucceedsWithZeroRecords() {
		var gateway = pages(start -> page(0, start, 0, 0));
		var run = runIngestion(gateway).run(job(Pagination.offset(500), ResponseShape.ENVELOPE));

		assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(run.records()).isZero();
		assertThat(events).hasSize(1);
	}

	@Test
	void sourceFailureMarksRunFailedWithoutEvent() {
		var gateway = pages(start -> {
			if (start == 500) {
				throw new SourceAccessException(Kind.SERVER_ERROR, 503, "GET x -> 503");
			}
			return page(start / 500, start, 500, 1251);
		});
		var run = runIngestion(gateway).run(job(Pagination.offset(500), ResponseShape.ENVELOPE));

		assertThat(run.status()).isEqualTo(RunStatus.FAILED);
		assertThat(run.error()).contains("SourceAccessException").contains("503");
		assertThat(run.pages()).isEqualTo(1);
		assertThat(payloads.stored).hasSize(1);
		assertThat(events).isEmpty();
		assertThat(runs.lastSucceeded(DATASET)).isEmpty();
		assertThat(runs.history(DATASET, 10)).singleElement().extracting(IngestionRun::status)
				.isEqualTo(RunStatus.FAILED);
	}

	@Test
	void handlerFailureMarksRunFailed() {
		var gateway = pages(start -> page(0, start, 3, 3));
		IngestionJob failing = new IngestionJob() {
			@Override
			public SourceDescriptor source() {
				return descriptor(Pagination.none(500), ResponseShape.ENVELOPE);
			}

			@Override
			public Duration interval() {
				return Duration.ofDays(1);
			}

			@Override
			public void handle(RawPage page) {
				throw new IllegalArgumentException("unexpected schema: field x missing");
			}
		};
		var run = runIngestion(gateway).run(failing);

		assertThat(run.status()).isEqualTo(RunStatus.FAILED);
		assertThat(run.error()).isEqualTo("IllegalArgumentException: unexpected schema: field x missing");
		assertThat(events).isEmpty();
	}

	@Test
	void guardsAgainstEndlessPagination() {
		var gateway = pages(start -> page(start / 500, start, 500, null));
		var run = new RunIngestion(gateway, runs, payloads, completion(), CLOCK, Duration.ZERO, 3)
				.run(job(Pagination.offset(500), ResponseShape.ARRAY));

		assertThat(run.status()).isEqualTo(RunStatus.FAILED);
		assertThat(run.error()).contains("more than 3 pages");
		assertThat(gateway.starts).containsExactly(0, 500, 1000);
	}

	// --- helpers ---------------------------------------------------------------------------------------------

	RunIngestion runIngestion(SourceGateway gateway) {
		return new RunIngestion(gateway, runs, payloads, completion(), CLOCK, Duration.ZERO, 1000);
	}

	CompleteIngestionRun completion() {
		IngestionEventPublisher publisher = events::add;
		return new CompleteIngestionRun(runs, publisher, CLOCK);
	}

	IngestionJob job(Pagination pagination, ResponseShape shape) {
		return new IngestionJob() {
			@Override
			public SourceDescriptor source() {
				return descriptor(pagination, shape);
			}

			@Override
			public Duration interval() {
				return Duration.ofDays(1);
			}

			@Override
			public void handle(RawPage page) {
				handled.add(page);
			}
		};
	}

	static SourceDescriptor descriptor(Pagination pagination, ResponseShape shape) {
		return new SourceDescriptor(DATASET, URL, Map.of(), pagination, shape);
	}

	static RawPage page(int number, int start, int records, Integer totalCount) {
		return new RawPage(number, start, URI.create(URL + "?rows=500&start=" + start), "application/json",
				"{\"result\":[]}", records, totalCount, null, null, NOW, Duration.ofMillis(100));
	}

	static RecordingGateway pages(IntFunction<RawPage> byStart) {
		return new RecordingGateway(byStart);
	}

	static final class RecordingGateway implements SourceGateway {
		final List<Integer> starts = new ArrayList<>();
		final IntFunction<RawPage> byStart;

		RecordingGateway(IntFunction<RawPage> byStart) {
			this.byStart = byStart;
		}

		@Override
		public RawPage fetch(SourceDescriptor source, int pageNumber, int start) {
			starts.add(start);
			return byStart.apply(start);
		}
	}

	static final class InMemoryRuns implements IngestionRunRepository {
		final Map<IngestionRunId, IngestionRun> byId = new LinkedHashMap<>();

		@Override
		public void save(IngestionRun run) {
			byId.put(run.id(), run);
		}

		@Override
		public Optional<IngestionRun> find(IngestionRunId id) {
			return Optional.ofNullable(byId.get(id));
		}

		@Override
		public Optional<IngestionRun> lastSucceeded(DatasetRef dataset) {
			return history(dataset, Integer.MAX_VALUE).stream().filter(r -> r.status() == RunStatus.SUCCEEDED)
					.findFirst();
		}

		@Override
		public List<IngestionRun> history(DatasetRef dataset, int limit) {
			return byId.values().stream().filter(r -> r.dataset().equals(dataset))
					.sorted((a, b) -> b.startedAt().compareTo(a.startedAt())).limit(limit).toList();
		}
	}

	static final class InMemoryPayloads implements RawPayloadStore {
		final List<RawPayload> stored = new ArrayList<>();

		@Override
		public void store(RawPayload payload) {
			stored.add(payload);
		}

		@Override
		public List<RawPayload> findByRun(IngestionRunId run) {
			return stored.stream().filter(p -> p.run().equals(run)).toList();
		}

		@Override
		public int purgeFetchedBefore(Instant threshold) {
			int before = stored.size();
			stored.removeIf(p -> p.fetchedAt().isBefore(threshold));
			return before - stored.size();
		}
	}

}
