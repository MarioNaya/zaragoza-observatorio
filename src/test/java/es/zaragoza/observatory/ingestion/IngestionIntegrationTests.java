package es.zaragoza.observatory.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.client.MockRestServiceServer;

import es.zaragoza.observatory.TestcontainersConfiguration;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.ingestion.domain.RawPayload;
import es.zaragoza.observatory.ingestion.domain.RawPayloadStore;
import es.zaragoza.observatory.shared.DatasetIngested;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;
import es.zaragoza.observatory.support.Fixtures;

/**
 * Recorrido completo con PostGIS real (Testcontainers) y la API municipal simulada con respuestas grabadas:
 * paginación, persistencia de {@code ingestion_run} y {@code raw_payload}, y publicación de {@code DatasetIngested}.
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@RecordApplicationEvents
class IngestionIntegrationTests {

	static final String CATALOG_URL = "https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json";
	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	@Autowired
	Ingestion ingestion;

	@Autowired
	RawPayloadStore payloads;

	@Autowired
	MockRestServiceServer server;

	@Autowired
	ApplicationEvents events;

	@Test
	void ingestsAllPagesPersistsRunAndPayloadsAndPublishesEvent() {
		var dataset = DatasetRef.of(Sources.DATA_SPACE, "catalogo-it-" + System.nanoTime());
		server.expect(requestTo(startsWith(CATALOG_URL + "?")))
				.andExpect(queryParam("start", "0"))
				.andExpect(header(HttpHeaders.USER_AGENT, startsWith("observatorio-zaragoza/")))
				.andRespond(withSuccess(Fixtures.bytes("catalog/catalogo-rows2-fl.json"), JSON_UTF8)
						.header(HttpHeaders.LAST_MODIFIED, "Tue, 20 Jan 2026 13:12:38 CET"));
		server.expect(requestTo(startsWith(CATALOG_URL + "?")))
				.andExpect(queryParam("start", "2"))
				.andRespond(withSuccess("{\"totalCount\":436,\"start\":2,\"rows\":2,\"result\":[{\"id\":99}]}",
						JSON_UTF8));
		var handled = new ArrayList<RawPage>();

		IngestionRunSummary run = ingestion.run(job(dataset, handled));

		server.verify();
		assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(run.pages()).isEqualTo(2);
		assertThat(run.records()).isEqualTo(3);
		assertThat(run.sourceLastModified()).isEqualTo(Instant.parse("2026-01-20T12:12:38Z"));
		assertThat(run.finishedAt()).isAfterOrEqualTo(run.startedAt());
		assertThat(handled).hasSize(2);

		assertThat(ingestion.lastSuccessful(dataset)).map(IngestionRunSummary::id).contains(run.id());
		assertThat(ingestion.history(dataset, 10)).hasSize(1);

		List<RawPayload> stored = payloads.findByRun(run.id());
		assertThat(stored).extracting(RawPayload::pageNumber).containsExactly(0, 1);
		assertThat(stored.get(0).body()).contains("\"totalCount\":436");
		assertThat(stored.get(0).sourceLastModified()).isEqualTo(Instant.parse("2026-01-20T12:12:38Z"));
		assertThat(stored.get(0).url().toString()).contains("rows=2").contains("start=0");

		assertThat(events.stream(DatasetIngested.class)).singleElement().satisfies(event -> {
			assertThat(event.dataset()).isEqualTo(dataset);
			assertThat(event.run()).isEqualTo(run.id());
			assertThat(event.records()).isEqualTo(3);
		});
	}

	@Test
	void persistentFailureIsRecordedWithoutEvent() {
		var dataset = DatasetRef.of(Sources.DATA_SPACE, "catalogo-ko-" + System.nanoTime());
		server.expect(times(3), requestTo(startsWith(CATALOG_URL + "?"))).andRespond(withServerError());

		IngestionRunSummary run = ingestion.run(job(dataset, new ArrayList<>()));

		server.verify();
		assertThat(run.status()).isEqualTo(RunStatus.FAILED);
		assertThat(run.error()).contains("500");
		assertThat(ingestion.lastSuccessful(dataset)).isEmpty();
		assertThat(ingestion.history(dataset, 10)).singleElement().extracting(IngestionRunSummary::status)
				.isEqualTo(RunStatus.FAILED);
		assertThat(events.stream(DatasetIngested.class).filter(e -> e.dataset().equals(dataset))).isEmpty();
	}

	static IngestionJob job(DatasetRef dataset, List<RawPage> handled) {
		return new IngestionJob() {
			@Override
			public SourceDescriptor source() {
				return new SourceDescriptor(dataset, URI.create(CATALOG_URL), Map.of("fl", "id,title,formato"),
						Pagination.offset(2), ResponseShape.ENVELOPE);
			}

			@Override
			public Duration interval() {
				return Duration.ofHours(6);
			}

			@Override
			public void handle(RawPage page) {
				handled.add(page);
			}
		};
	}

}
