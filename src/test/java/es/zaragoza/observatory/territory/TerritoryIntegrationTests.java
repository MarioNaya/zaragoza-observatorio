package es.zaragoza.observatory.territory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import es.zaragoza.observatory.TestcontainersConfiguration;
import es.zaragoza.observatory.citizen.CitizenSources;
import es.zaragoza.observatory.geo.GeoSources;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.support.Fixtures;
import es.zaragoza.observatory.urban.UrbanSources;

/**
 * El cruce territorial de punta a punta (ADR-019), con PostGIS real y las dos fuentes territoriales ingeridas de
 * sus fixtures: es el primer sitio del proyecto donde dos módulos de dominio acaban en la misma tabla.
 * <p>
 * Lo que solo se puede comprobar aquí es que la composición no miente sobre lo que compone: que <b>la suma de
 * las 29 filas de una columna es {@code assigned} y no {@code total}</b>, que cada columna trae su propia
 * cobertura, y que pedir una medida de gasto por junta se rechaza <b>diciendo por qué</b>.
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S", "zaragoza.catalog.observation.enabled=false",
		"zaragoza.geo.profile-request-delay=PT0S" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@AutoConfigureMockMvc
class TerritoryIntegrationTests {

	static final String COMPLAINTS_URL = "https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json";
	static final String PREMISES_URL = "https://www.zaragoza.es/sede/servicio/registro-licencia.json";
	static final String DISTRICTS_URL = "https://www.zaragoza.es/sede/servicio/distrito.json";
	static final String DETAIL_PREFIX = "https://www.zaragoza.es/sede/servicio/distrito/";
	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");
	static final String EMPTY_PREMISES_PAGE = "{\"totalCount\":42342,\"start\":500,\"rows\":500,\"result\":[]}";

	@Autowired
	Ingestion ingestion;

	@Autowired
	ObjectProvider<IngestionJob> jobs;

	@Autowired
	MockRestServiceServer server;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void crossesTwoDomainsOnTheDistrictAxisWithTheirDenominatorAndCoverage() {
		loadDistricts();
		ingestBothSources();

		// --- la matriz: 29 filas, una columna por medida ----------------------------------------------------
		var matrix = assertThat(mvc.get().uri("/api/v1/territory/districts"))
				.hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON).bodyJson();
		matrix.extractingPath("$.item.axis").isEqualTo("district");
		matrix.extractingPath("$.item.districts").isEqualTo(29);
		matrix.extractingPath("$.item.items").asArray().hasSize(29);
		matrix.extractingPath("$.item.sort").isEqualTo("district,asc");
		// Sin `measures` salen las tres del catálogo: no elegir cruce no es elegir uno (ADR-019 §2).
		matrix.extractingPath("$.item.measures[*].id").asArray()
				.containsExactly("citizen.requests", "urban.premises", "urban.licences");
		matrix.extractingPath("$.item.measures[*].unit").asArray()
				.containsExactly("requests", "premises", "licences");
		// No hay ingestedAt único en la raíz: hay uno por medida, y fingir uno solo sería mentir (ADR-019 §2).
		matrix.extractingPath("$.item").asMap().doesNotContainKeys("ingestedAt", "source");
		matrix.extractingPath("$.item.measures[0].ingestedAt").isNotNull();
		matrix.extractingPath("$.caveats").asArray().isNotEmpty();

		// --- regla 7: la cobertura viaja y la suma de las filas no es el total ------------------------------
		long assigned = ((Number) json("$.item.measures[0].coverage.assigned")).longValue();
		long total = ((Number) json("$.item.measures[0].coverage.total")).longValue();
		long unassigned = ((Number) json("$.item.measures[0].coverage.unassigned")).longValue();
		assertThat(total).isEqualTo(500);
		assertThat(unassigned).isEqualTo(total - assigned);
		long rowSum = 0;
		for (int i = 0; i < 29; i++) {
			rowSum += ((Number) json("$.item.items[" + i + "].values['citizen.requests']")).longValue();
		}
		assertThat(rowSum).as("las 29 filas suman lo asignado, no el total (ADR-019 §6)").isEqualTo(assigned);
		assertThat(rowSum).isLessThan(total);

		// --- las dos medidas de urban cuentan unidades distintas sobre los mismos locales -------------------
		long premisesTotal = ((Number) json("$.item.measures[1].coverage.total")).longValue();
		long licencesTotal = ((Number) json("$.item.measures[2].coverage.total")).longValue();
		assertThat(premisesTotal).isEqualTo(500);
		assertThat(licencesTotal).as("un local con doce licencias es un local y son doce licencias")
				.isGreaterThan(premisesTotal);

		// --- denominador: padrón por junta con su año, nunca implícito (regla 7) ----------------------------
		matrix.extractingPath("$.item.denominator").isEqualTo("population");
		matrix.extractingPath("$.item.items[0].populationYear").isNotNull();
		matrix.extractingPath("$.item.items[0].population").isNotNull();
		matrix.extractingPath("$.item.items[0].perThousandInhabitants['citizen.requests']").isNotNull();
		// Y ninguna columna derivada de dos medidas: el producto no divide una medida por otra (ADR-019 §4).
		matrix.extractingPath("$.item.items[0]").asMap()
				.doesNotContainKeys("requestsPerPremises", "ratio", "index");

		assertThat(mvc.get().uri("/api/v1/territory/districts").param("denominator", "none"))
				.hasStatusOk().bodyJson().extractingPath("$.item.items[*].population").asArray()
				.allSatisfy(population -> assertThat(population).isNull());

		// --- ordenación explícita y desempate estable (regla 8) ---------------------------------------------
		var sorted = assertThat(mvc.get().uri("/api/v1/territory/districts")
				.param("measures", "citizen.requests").param("sort", "citizen.requests,desc"))
				.hasStatusOk().bodyJson();
		sorted.extractingPath("$.item.sort").isEqualTo("citizen.requests,desc");
		var values = sorted.extractingPath("$.item.items[*].values['citizen.requests']").convertTo(
				org.assertj.core.api.InstanceOfAssertFactories.list(Integer.class)).actual();
		assertThat(values).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		// Solo se ordena por una medida pedida: por una ausente no se puede comprobar el orden.
		assertThat(mvc.get().uri("/api/v1/territory/districts").param("measures", "citizen.requests")
				.param("sort", "urban.premises,desc")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/territory/districts").param("sort", "inventado,asc"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- el catálogo es cerrado, y el gasto se rechaza diciendo por qué (ADR-019 §8) --------------------
		assertThat(mvc.get().uri("/api/v1/territory/districts").param("measures", "citizen.text"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		var rejected = assertThat(mvc.get().uri("/api/v1/territory/districts")
				.param("measures", "spending.awarded")).hasStatus(HttpStatus.BAD_REQUEST);
		rejected.bodyText().contains("no puede aportar una medida territorial");
		assertThat(mvc.get().uri("/api/v1/territory/districts").param("denominator", "inventado"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- un subconjunto de medidas deja solo esas columnas ----------------------------------------------
		var single = assertThat(mvc.get().uri("/api/v1/territory/districts")
				.param("measures", "urban.premises")).hasStatusOk().bodyJson();
		single.extractingPath("$.item.measures[*].id").asArray().containsExactly("urban.premises");
		single.extractingPath("$.item.items[0].values").asMap().containsOnlyKeys("urban.premises");

		// --- la ficha de una junta, con la serie de padrón entera -------------------------------------------
		var card = assertThat(mvc.get().uri("/api/v1/territory/districts/6")).hasStatusOk().bodyJson();
		card.extractingPath("$.item.item.districtId").isEqualTo(6);
		card.extractingPath("$.item.item.shortName").isNotNull();
		card.extractingPath("$.item.measures[*].id").asArray().hasSize(3);
		// La serie va entera y con sus huecos: el padrón municipal tiene 2020, 2021, 2022 y 2024, sin 2023.
		card.extractingPath("$.item.populationSeries[*].year").asArray().hasSize(4)
				.containsExactly(2020, 2021, 2022, 2024);
		assertThat(mvc.get().uri("/api/v1/territory/districts/999")).hasStatus(HttpStatus.NOT_FOUND);

		// --- la ventana se aplica y estrecha las columnas ---------------------------------------------------
		long windowed = ((Number) jsonOf(mvc.get().uri("/api/v1/territory/districts")
				.param("measures", "citizen.requests").param("from", "2030-01-01T00:00:00Z"),
				"$.item.measures[0].coverage.total")).longValue();
		assertThat(windowed).as("una ventana en el futuro no deja ninguna queja dentro").isZero();
		assertThat(mvc.get().uri("/api/v1/territory/districts").param("from", "2030-01-01T00:00:00Z")
				.param("to", "2020-01-01T00:00:00Z")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	// --- montaje ---------------------------------------------------------------------------------------

	private void ingestBothSources() {
		server.expect(requestTo(Matchers.startsWith(COMPLAINTS_URL)))
				.andRespond(withSuccess(Fixtures.text("open311/sede-list-ingest-page.json"), JSON_UTF8));
		server.expect(requestTo(Matchers.startsWith(COMPLAINTS_URL)))
				.andRespond(withSuccess("[]", JSON_UTF8));
		assertThat(ingestion.run(job(CitizenSources.REQUESTS)).status()).isEqualTo(RunStatus.SUCCEEDED);
		// El servidor simulado no admite expectativas nuevas después de haber servido peticiones: cada fuente
		// monta las suyas sobre un servidor limpio.
		server.reset();

		server.expect(requestTo(Matchers.startsWith(PREMISES_URL))).andRespond(
				withSuccess(Fixtures.text("urban/registro-licencia_page0_rows-500.json"), JSON_UTF8));
		server.expect(requestTo(Matchers.startsWith(PREMISES_URL)))
				.andRespond(withSuccess(EMPTY_PREMISES_PAGE, JSON_UTF8));
		assertThat(ingestion.run(job(UrbanSources.PREMISES)).status()).isEqualTo(RunStatus.SUCCEEDED);
		server.reset();
	}

	/** Las 29 juntas reales con su geometría y su padrón, como en los tests de {@code citizen} y {@code urban}. */
	private void loadDistricts() {
		server.expect(requestTo(Matchers.startsWith(DISTRICTS_URL)))
				.andRespond(withSuccess(Fixtures.text("geo/distrito.json_srsname-wgs84_rows-100"), JSON_UTF8));
		String detail = Fixtures.text("geo/distrito-6-indicadores.json");
		server.expect(ExpectedCount.times(29), requestTo(Matchers.startsWith(DETAIL_PREFIX)))
				.andRespond(request -> {
					String path = request.getURI().getPath();
					int id = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1).replace(".json", ""));
					String body = detail.replace("{\"id\":6,", "{\"id\":" + id + ",")
							.replace("\"iddatosab\":6", "\"iddatosab\":" + id)
							.replace("\"idpadron\":15", "\"idpadron\":" + (id + 100));
					return withSuccess(body, JSON_UTF8).createResponse(request);
				});
		assertThat(ingestion.run(job(GeoSources.DISTRICTS)).status()).isEqualTo(RunStatus.SUCCEEDED);
		// El padrón lo trae un listener asíncrono de geo y el denominador lo necesita (regla 7): se espera a que
		// el registro de eventos de Modulith no tenga ninguna publicación a medias.
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc
				.sql("select count(*) from event_publication where completion_date is null").query(Long.class)
				.single()).isZero());
		server.reset();
	}

	private IngestionJob job(DatasetRef dataset) {
		return jobs.stream().filter(j -> dataset.equals(j.source().dataset())).findFirst().orElseThrow();
	}

	private Object json(String path) {
		return jsonOf(mvc.get().uri("/api/v1/territory/districts"), path);
	}

	private Object jsonOf(MockMvcTester.MockMvcRequestBuilder request, String path) {
		return assertThat(request).hasStatusOk().bodyJson().extractingPath(path).actual();
	}

}
