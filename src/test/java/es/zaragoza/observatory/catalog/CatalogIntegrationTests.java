package es.zaragoza.observatory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import es.zaragoza.observatory.TestcontainersConfiguration;
import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.application.ObserveDatasets;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;
import es.zaragoza.observatory.catalog.domain.ObservationMethod;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.support.Fixtures;

/**
 * Fase 1 de punta a punta con PostGIS real y el catálogo municipal simulado con la respuesta grabada el
 * 2026-09-06: ingesta idempotente, listener asíncrono de Modulith con registro en {@code event_publication},
 * instantáneas de frescura y API REST del monitor.
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S", "zaragoza.catalog.observation.enabled=false",
		"zaragoza.catalog.observation.request-delay=PT0S" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@AutoConfigureMockMvc
class CatalogIntegrationTests {

	static final String CATALOG_URL = "https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json";
	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	@Autowired
	Ingestion ingestion;

	@Autowired
	ObjectProvider<IngestionJob> jobs;

	@Autowired
	DatasetRepository datasets;

	@Autowired
	FreshnessSnapshotRepository snapshots;

	@Autowired
	ObserveDatasets observeDatasets;

	@Autowired
	MockRestServiceServer server;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void ingestsTheCatalogTakesSnapshotsAndServesTheMonitor() {
		IngestionJob job = catalogJob();
		assertThat(job.source().url()).hasToString(CATALOG_URL);
		assertThat(job.source().query()).containsEntry("fl",
				"id,title,description_basic,issued,modified,lastUpdated,accrualPeriodicity,status,geo,abierto,explorable,formato");
		assertThat(job.interval()).isEqualTo(Duration.ofHours(6));

		// --- primera ingesta: una página de 436 (totalCount 436 < rows 500) --------------------------------------
		expectCatalogPage();
		IngestionRunSummary first = ingestion.run(job);
		server.verify();
		assertThat(first.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(first.pages()).isEqualTo(1);
		assertThat(first.records()).isEqualTo(436);
		assertThat(datasets.count()).isEqualTo(436);

		Dataset arte = datasets.findBySourceId(13).orElseThrow();
		assertThat(arte.title()).isEqualTo("Arte Público");
		assertThat(arte.periodicityDays()).isEqualTo(90);
		assertThat(arte.apiTag()).isEqualTo("Cultura: Arte en la via publica");
		assertThat(arte.distributions()).hasSize(1);
		assertThat(arte.firstSeenAt()).isEqualTo(arte.lastSeenAt());

		// --- el listener asíncrono de Modulith toma las instantáneas y completa la publicación -------------------
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(snapshots.latest(13)).isPresent());
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc
				.sql("select count(*) from event_publication where completion_date is not null and listener_id like :l")
				.param("l", "%CatalogIngestedListener%").query(Long.class).single()).isGreaterThanOrEqualTo(1L));
		assertThat(snapshots.latest(13)).get().satisfies(s -> {
			assertThat(s.declared()).isEqualTo(DeclaredFreshness.NOT_UPDATED); // P3M con modified de 2019
			assertThat(s.periodicityDays()).isEqualTo(90);
			assertThat(s.declaredRatio()).isGreaterThan(20);
		});
		long notEvaluable = datasets.findAll().stream()
				.filter(d -> d.periodicityDays() == null || d.declaredModified() == null).count();
		assertThat(notEvaluable).isGreaterThan(259); // 259 sin periodicidad evaluable (S0.1) + sin modified

		// --- segunda ingesta: idempotente (regla 5) ---------------------------------------------------------------
		server.reset();
		expectCatalogPage();
		IngestionRunSummary second = ingestion.run(job);
		server.verify();
		assertThat(second.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(datasets.count()).isEqualTo(436);
		Dataset arteAgain = datasets.findBySourceId(13).orElseThrow();
		assertThat(arteAgain.firstSeenAt()).isEqualTo(arte.firstSeenAt());
		assertThat(arteAgain.lastSeenAt()).isAfterOrEqualTo(arte.lastSeenAt());
		assertThat(arteAgain.distributions()).hasSize(1);
		assertThat(ingestion.history(CatalogSources.CATALOG, 10)).hasSize(2);

		// --- eje observado (S1.1): tres fichas reales con las respuestas grabadas por el spike ---------------------
		server.reset();
		String incidencia = "https://www.zaragoza.es/sede/servicio/via-publica/incidencia.json?rows=1&srsname=wgs84";
		server.expect(requestTo(incidencia)).andRespond(withSuccess(
				Fixtures.bytes("catalog/observation/api-sede-servicio-via-publica-incidencia-rows1.json"), JSON_UTF8));
		server.expect(requestTo(incidencia + "&sort=lastUpdated+desc")).andRespond(withSuccess(
				Fixtures.bytes("catalog/observation/api-sede-servicio-via-publica-incidencia-sort-lastUpdated.json"),
				JSON_UTF8));
		server.expect(requestTo("https://www.zaragoza.es/cont/paginas/estadistica/pdf/Apendice_Boletin1.xls"))
				.andExpect(method(HttpMethod.HEAD))
				.andRespond(withSuccess().headers(Fixtures.headers("catalog/observation/head-xls-216-425.headers")));
		server.expect(requestTo("https://www.zaragoza.es/cont/paginas/estadistica/pdf/Apendice_Boletin1.ods"))
				.andExpect(method(HttpMethod.HEAD))
				.andRespond(withSuccess().headers(Fixtures.headers("catalog/observation/head-ods-216-856.headers")));
		server.expect(requestTo("https://idezar-sig.zaragoza.es/servicios/geoserver/urbanismo/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=Vias&resultType=hits"))
				.andRespond(withSuccess(Fixtures.bytes("catalog/observation/wfs-hits-Vias.xml"), MediaType.APPLICATION_XML));
		observeDatasets.observe(datasets.findBySourceId(67).orElseThrow());
		observeDatasets.observe(datasets.findBySourceId(216).orElseThrow());
		observeDatasets.observe(datasets.findBySourceId(22).orElseThrow());
		server.verify();
		assertThat(snapshots.latest(67)).get().satisfies(s -> {
			assertThat(s.declared()).isEqualTo(DeclaredFreshness.NOT_EVALUABLE); // P0DT1S
			assertThat(s.observationMethod()).isEqualTo(ObservationMethod.API_MAX_DATE);
			assertThat(s.observedLastChange()).isEqualTo(java.time.Instant.parse("2026-09-04T12:23:47Z"));
			assertThat(s.observedRecords()).isEqualTo(67);
			assertThat(s.observationDetail()).isEqualTo("lastUpdated");
			assertThat(s.observationError()).isNull();
		});
		assertThat(snapshots.latest(216)).get().extracting(s -> s.observationMethod())
				.isEqualTo(ObservationMethod.FILE_HEADERS);
		assertThat(snapshots.latest(22)).get().satisfies(s -> {
			assertThat(s.observationMethod()).isEqualTo(ObservationMethod.WFS_HITS);
			assertThat(s.observedRecords()).isEqualTo(3359);
		});
		assertThat(jdbc.sql("select count(*) from catalog_dataset where observed_at is not null").query(Long.class)
				.single()).isEqualTo(3L);
		assertThat(datasets.findDueForObservation(java.time.Instant.now().minusSeconds(60), 1000))
				.extracting(Dataset::sourceId).doesNotContain(67, 216, 22).hasSize(433);

		// --- API REST: listado ordenado y paginado con origen, fecha de ingesta y caveats -----------------------
		var listing = assertThat(
				mvc.get().uri("/api/v1/catalog/datasets").param("size", "5").param("sort", "declaredModified,desc"))
				.hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON).bodyJson();
		listing.extractingPath("$.page.totalElements").isEqualTo(436);
		listing.extractingPath("$.page.size").isEqualTo(5);
		listing.extractingPath("$.page.totalPages").isEqualTo(88);
		listing.extractingPath("$.page.sort").isEqualTo("declaredModified,desc");
		listing.extractingPath("$.items").asArray().hasSize(5);
		listing.extractingPath("$.items[0].declaredModified").asString().startsWith("2026-08-11");
		listing.extractingPath("$.source.dataset").isEqualTo("data-space:catalogo");
		listing.extractingPath("$.source.url").isEqualTo(CATALOG_URL);
		listing.extractingPath("$.ingestedAt").asString().isNotEmpty();
		listing.extractingPath("$.caveats").asArray().isNotEmpty();

		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("freshness", "NOT_EVALUABLE").param("size", "1"))
				.hasStatusOk().bodyJson().extractingPath("$.page.totalElements").isEqualTo((int) notEvaluable);

		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("hasApi", "true").param("size", "1"))
				.hasStatusOk().bodyJson().extractingPath("$.page.totalElements").isEqualTo(69);

		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("q", "arte público").param("size", "10"))
				.hasStatusOk().bodyJson().extractingPath("$.items[*].id").asArray().contains(13);

		// --- detalle, histórico y resumen ---------------------------------------------------------------------------
		var detail = assertThat(mvc.get().uri("/api/v1/catalog/datasets/13")).hasStatusOk().bodyJson();
		detail.extractingPath("$.item.title").isEqualTo("Arte Público");
		detail.extractingPath("$.item.declaredPeriodicity").isEqualTo("P3M");
		detail.extractingPath("$.item.latestSnapshot.declared").isEqualTo("NOT_UPDATED");
		detail.extractingPath("$.item.latestSnapshot.observedLastChange").isNull();
		detail.extractingPath("$.item.latestObservationMethod").isNull();

		var observedDetail = assertThat(mvc.get().uri("/api/v1/catalog/datasets/67")).hasStatusOk().bodyJson();
		observedDetail.extractingPath("$.item.latestObservationMethod").isEqualTo("API_MAX_DATE");
		observedDetail.extractingPath("$.item.latestObservedChange").isEqualTo("2026-09-04T12:23:47Z");
		observedDetail.extractingPath("$.item.latestSnapshot.observationMethod").isEqualTo("API_MAX_DATE");
		observedDetail.extractingPath("$.item.latestSnapshot.observedRecords").isEqualTo(67);
		observedDetail.extractingPath("$.item.latestSnapshot.observationDetail").isEqualTo("lastUpdated");
		observedDetail.extractingPath("$.item.latestSnapshot.observedUrl").asString().contains("sort=lastUpdated");

		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("observation", "API_MAX_DATE").param("size", "5"))
				.hasStatusOk().bodyJson().extractingPath("$.items[*].id").asArray().containsExactly(67);
		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("sort", "observedLastChange,desc").param("size", "2"))
				.hasStatusOk().bodyJson().extractingPath("$.items[0].id").isEqualTo(67);
		detail.extractingPath("$.item.distributions[0].mediaType").isEqualTo("application/api");
		detail.extractingPath("$.item.apiTag").isEqualTo("Cultura: Arte en la via publica");

		var history = assertThat(mvc.get().uri("/api/v1/catalog/datasets/13/freshness-history")).hasStatusOk()
				.bodyJson();
		history.extractingPath("$.sort").isEqualTo("observedOn,desc");
		history.extractingPath("$.items").asArray().hasSize(1);
		history.extractingPath("$.items[0].declared").isEqualTo("NOT_UPDATED");

		var summary = assertThat(mvc.get().uri("/api/v1/catalog/summary")).hasStatusOk().bodyJson();
		summary.extractingPath("$.datasets").isEqualTo(436);
		summary.extractingPath("$.byPeriodicity.P1Y").isEqualTo(135);
		summary.extractingPath("$.byPeriodicity.UNDECLARED").isEqualTo(124);
		summary.extractingPath("$.withApi").isEqualTo(69);
		summary.extractingPath("$.explorable").isEqualTo(110);
		summary.extractingPath("$.withoutSnapshot").isEqualTo(0);
		summary.extractingPath("$.thresholds.onTimeMax").isEqualTo(1.0);
		summary.extractingPath("$.byDeclaredFreshness").asMap().satisfies(map -> assertThat(
				map.values().stream().mapToLong(v -> ((Number) v).longValue()).sum()).isEqualTo(436));
		summary.extractingPath("$.byDeclaredFreshness.NOT_EVALUABLE").isEqualTo((int) notEvaluable);
		summary.extractingPath("$.latestSnapshotOn").asString().isNotEmpty();
		summary.extractingPath("$.byObservationMethod.API_MAX_DATE").isEqualTo(1);
		summary.extractingPath("$.byObservationMethod.FILE_HEADERS").isEqualTo(1);
		summary.extractingPath("$.byObservationMethod.WFS_HITS").isEqualTo(1);
		summary.extractingPath("$.byObservationMethod.NOT_OBSERVABLE").isEqualTo(0);
		summary.extractingPath("$.withoutObservation").isEqualTo(433);
	}

	@Test
	void publishesAnOpenApiContractForTheMonitor() {
		var api = assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyJson();
		api.extractingPath("$.info.title").isEqualTo("Observatorio de Datos Abiertos de Zaragoza");
		api.extractingPath("$.paths").asMap().containsKeys("/api/v1/catalog/datasets",
				"/api/v1/catalog/datasets/{id}", "/api/v1/catalog/datasets/{id}/freshness-history",
				"/api/v1/catalog/summary");
	}

	@Test
	void unknownDatasetIsAProblemDetail() {
		assertThat(mvc.get().uri("/api/v1/catalog/datasets/999999")).hasStatus(HttpStatus.NOT_FOUND)
				.hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
				.extractingPath("$.status").isEqualTo(404);
	}

	@Test
	void invalidPagingSortOrFilterIsRejectedWith400() {
		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("sort", "foo,asc")).hasStatus(HttpStatus.BAD_REQUEST)
				.bodyJson().extractingPath("$.detail").asString().contains("sort no admitido");
		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("sort", "title,sideways"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("size", "500")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/catalog/datasets").param("freshness", "bogus"))
				.hasStatus(HttpStatus.BAD_REQUEST);
	}

	private IngestionJob catalogJob() {
		return jobs.stream().filter(j -> j.source().dataset().equals(CatalogSources.CATALOG)).findFirst()
				.orElseThrow();
	}

	private void expectCatalogPage() {
		server.expect(requestTo(startsWith(CATALOG_URL + "?")))
				.andExpect(queryParam("rows", "500"))
				.andExpect(queryParam("start", "0"))
				.andRespond(withSuccess(Fixtures.bytes("catalog/catalogo-rows500-fl.json"), JSON_UTF8)
						.header(HttpHeaders.LAST_MODIFIED, "Tue, 20 Jan 2026 13:12:38 CET"));
	}

}
