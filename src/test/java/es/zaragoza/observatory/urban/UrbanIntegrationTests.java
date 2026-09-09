package es.zaragoza.observatory.urban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

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
import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.geo.GeoSources;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.support.Fixtures;
import es.zaragoza.observatory.urban.domain.LicensedPremises;
import es.zaragoza.observatory.urban.domain.PremisesQuery;
import es.zaragoza.observatory.urban.domain.PremisesRepository;

/**
 * El módulo {@code urban} de punta a punta con PostGIS real y la API municipal simulada con la página que grabó
 * S2.4: ingesta de registros completos, resolución territorial por {@code ST_Contains} contra las 29 juntas
 * reales, licencias reemplazadas por local, upsert idempotente y la API de lectura.
 * <p>
 * Tres cosas se comprueban aquí porque solo aquí se pueden comprobar: que <b>ninguna columna contiene texto
 * libre</b>, que <b>la página cruda no se guarda</b> (las dos mitades de ADR-016 §3, no en el papel), y que las
 * agregaciones y sus filtros funcionan sobre PostgreSQL, que es donde vive el SQL del adaptador.
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S", "zaragoza.catalog.observation.enabled=false",
		"zaragoza.geo.profile-request-delay=PT0S" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@AutoConfigureMockMvc
class UrbanIntegrationTests {

	static final String LIST_URL = "https://www.zaragoza.es/sede/servicio/registro-licencia.json";
	static final String DISTRICTS_URL = "https://www.zaragoza.es/sede/servicio/distrito.json";
	static final String DETAIL_PREFIX = "https://www.zaragoza.es/sede/servicio/distrito/";
	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	/** Página vacía con el mismo envoltorio, para que la paginación por offset se detenga. */
	static final String EMPTY_PAGE = "{\"totalCount\":42342,\"start\":500,\"rows\":500,\"result\":[]}";

	@Autowired
	Ingestion ingestion;

	@Autowired
	ObjectProvider<IngestionJob> jobs;

	@Autowired
	PremisesRepository premises;

	@Autowired
	MockRestServiceServer server;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void ingestsPremisesResolvesTheirDistrictAndServesTheApi() {
		loadDistricts();

		// --- ingesta: registros completos y paginación por offset -------------------------------------------
		expectListingPage(Fixtures.text("urban/registro-licencia_page0_rows-500.json"));
		expectListingPage(EMPTY_PAGE);
		IngestionRunSummary run = ingestion.run(job(UrbanSources.PREMISES));

		assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(run.records()).isEqualTo(500);
		assertThat(premises.count()).isEqualTo(500);
		assertThat(premises.countLicences()).isPositive();

		// --- ADR-016 §3: ni columna de texto libre, ni página cruda guardada --------------------------------
		assertThat(columnsOf("urban_premises"))
				.doesNotContain("comments", "actividad", "emplazamiento", "activity", "address");
		assertThat(columnsOf("urban_premises_licence")).doesNotContain("comments");
		// La respuesta trae el texto sí o sí (`fl` rompe los anidados y `removeproperties` no hace nada, S2.4
		// §2), así que la única forma de que no quede rastro es que no se guarde la página.
		assertThat(jdbc.sql("""
				select count(*) from raw_payload where source = 'sede' and dataset_id = 'registro-licencia'
				""").query(Long.class).single())
				.as("la página cruda de esta fuente no se conserva (ADR-016 §3)").isZero();
		// Y las demás fuentes sí la conservan: la excepción es de este job, no del sistema.
		assertThat(jdbc.sql("select count(*) from raw_payload where dataset_id = 'distrito'").query(Long.class)
				.single()).isPositive();

		// --- ADR-011: territorio resuelto por geometría, y sin junta declarada ------------------------------
		var counts = premises.assignmentCounts(PremisesQuery.all());
		assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(500);
		assertThat(counts.get(Assignment.RESOLVED)).isPositive();
		// Esta fuente no declara junta por ninguna vía (S2.4 §6): no hay columna que la guarde.
		assertThat(columnsOf("urban_premises")).doesNotContain("district_declared", "district_declared_id");

		// Un registro sin punto nunca tiene junta, aunque el origen traiga dirección (ADR-011 §2).
		assertThat(jdbc.sql("select count(*) from urban_premises where lon is null and district_id is not null")
				.query(Long.class).single()).isZero();
		// La junta resuelta existe de verdad entre las 29 (no hay clave ajena entre módulos: se comprueba aquí).
		assertThat(jdbc.sql("""
				select count(*) from urban_premises p
				where p.district_id is not null
				  and not exists (select 1 from geo_district d where d.id = p.district_id)
				""").query(Long.class).single()).isZero();

		// --- el modelo: un local con N licencias, clave (local, año, expediente) ----------------------------
		assertThat(jdbc.sql("""
				select count(*) from (
				  select premises_id, year, file_number from urban_premises_licence
				  group by 1, 2, 3 having count(*) > 1) duplicated
				""").query(Long.class).single()).isZero();
		// El `orden` del origen no vale como clave: en el registro entero colisiona 950 veces (S2.4 §7). Que en
		// esta página haya o no colisiones da igual; lo que importa es que la tabla no dependa de él.
		assertThat(columnsOf("urban_premises_licence")).contains("display_order");

		// --- segunda ingesta: idempotente y con licencias reemplazadas, no acumuladas -----------------------
		long licencesBefore = premises.countLicences();
		LicensedPremises before = premises.search(PremisesQuery.all()).items().get(0);
		server.reset();
		expectListingPage(Fixtures.text("urban/registro-licencia_page0_rows-500.json"));
		expectListingPage(EMPTY_PAGE);
		assertThat(ingestion.run(job(UrbanSources.PREMISES)).status()).isEqualTo(RunStatus.SUCCEEDED);

		assertThat(premises.count()).as("upsert idempotente por id (regla 5)").isEqualTo(500);
		assertThat(premises.countLicences()).as("las licencias se reemplazan, no se acumulan")
				.isEqualTo(licencesBefore);
		LicensedPremises after = premises.search(PremisesQuery.all()).items().get(0);
		assertThat(after.firstSeenAt()).isEqualTo(before.firstSeenAt());
		assertThat(after.lastSeenAt()).isAfterOrEqualTo(before.lastSeenAt());

		// La segunda ejecución ya lleva marca de agua, y sigue ordenando por id (ADR-016 §4).
		var second = job(UrbanSources.PREMISES).source().query();
		assertThat(second).containsEntry("sort", "id asc");
		assertThat(second.get("q")).isNotNull().asString().startsWith("lastUpdated=ge=");

		// --- API: listado -----------------------------------------------------------------------------------
		var listing = assertThat(mvc.get().uri("/api/v1/urban/premises").param("size", "5"))
				.hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON).bodyJson();
		listing.extractingPath("$.total").isEqualTo(500);
		listing.extractingPath("$.items").asArray().hasSize(5);
		listing.extractingPath("$.source.dataset").isEqualTo("sede:registro-licencia");
		listing.extractingPath("$.caveats").asArray().isNotEmpty();
		listing.extractingPath("$.items[0].iaeCode").isNotNull();
		listing.extractingPath("$.items[0].licences").asArray().isNotNull();
		// El DTO no tiene ningún campo de texto libre del origen.
		listing.extractingPath("$.items[0]").asMap()
				.doesNotContainKeys("comments", "activity", "address", "emplazamiento", "districtDeclared");

		assertThat(mvc.get().uri("/api/v1/urban/premises").param("assignment", "no_point"))
				.hasStatusOk().bodyJson().extractingPath("$.items[*].districtId").asArray()
				.allSatisfy(id -> assertThat(id).isNull());
		assertThat(mvc.get().uri("/api/v1/urban/premises").param("sort", "inventado,asc"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/urban/premises").param("assignment", "inventado"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/urban/premises").param("size", "5000"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- API: agregaciones, con la unidad declarada y el denominador al lado ----------------------------
		var byDistrict = assertThat(mvc.get().uri("/api/v1/urban/aggregations").param("by", "district"))
				.hasStatusOk().bodyJson();
		byDistrict.extractingPath("$.item.by").isEqualTo("district");
		byDistrict.extractingPath("$.item.unit").isEqualTo("premises");
		byDistrict.extractingPath("$.item.buckets").asArray().isNotEmpty();
		byDistrict.extractingPath("$.item.buckets[0].population").isNotNull();
		byDistrict.extractingPath("$.item.buckets[0].perThousandInhabitants").isNotNull();
		byDistrict.extractingPath("$.item.buckets[0].pointCoverage").isEqualTo(1.0);
		byDistrict.extractingPath("$.item.buckets[0].label").isNotNull();
		byDistrict.extractingPath("$.item.unassigned").isNotNull();
		byDistrict.extractingPath("$.item.assignment.NO_POINT").isNotNull();

		var byActivity = assertThat(mvc.get().uri("/api/v1/urban/aggregations").param("by", "activity"))
				.hasStatusOk().bodyJson();
		byActivity.extractingPath("$.item.unit").isEqualTo("premises");
		byActivity.extractingPath("$.item.buckets[0].label").isNotNull();
		byActivity.extractingPath("$.item.matched").isEqualTo(500);

		// El eje de licencias cuenta otra unidad, y lo dice. Sumar las dos cifras sería un sinsentido, así que
		// cada grupo trae las dos por separado (ADR-016 §7).
		var byYear = assertThat(mvc.get().uri("/api/v1/urban/aggregations").param("by", "licence_year"))
				.hasStatusOk().bodyJson();
		byYear.extractingPath("$.item.unit").isEqualTo("licences");
		byYear.extractingPath("$.item.matched").isEqualTo((int) premises.countLicences());
		byYear.extractingPath("$.item.buckets[0].premises").isNotNull();
		byYear.extractingPath("$.item.buckets[0].licences").isNotNull();
		byYear.extractingPath("$.item.coverageByYear").asArray().isNotEmpty();

		assertThat(mvc.get().uri("/api/v1/urban/aggregations").param("by", "status"))
				.hasStatusOk().bodyJson().extractingPath("$.item.unit").isEqualTo("premises");
		assertThat(mvc.get().uri("/api/v1/urban/aggregations").param("by", "licence_type"))
				.hasStatusOk().bodyJson().extractingPath("$.item.buckets[0].label").isNotNull();
		assertThat(mvc.get().uri("/api/v1/urban/aggregations").param("by", "barrio"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// La serie territorial: un grupo por junta y año de licencia, con el padrón de ese año.
		var series = assertThat(mvc.get().uri("/api/v1/urban/aggregations")
				.param("by", "district_licence_year")).hasStatusOk().bodyJson();
		series.extractingPath("$.item.unit").isEqualTo("licences");
		series.extractingPath("$.item.buckets").asArray().isNotEmpty();
		series.extractingPath("$.item.buckets[0].year").isNotNull();
		// Dentro de un grupo territorial la cobertura vale siempre 1 por construcción: la que sirve para
		// comparar años va aparte (ADR-015).
		series.extractingPath("$.item.buckets[*].pointCoverage").asArray().containsOnly(1.0);
		series.extractingPath("$.item.coverageByYear").asArray().isNotEmpty();
		series.extractingPath("$.caveats").asArray()
				.anySatisfy(caveat -> assertThat(caveat.toString()).contains("padrón"));
		// Los años sin padrón salen sin denominador: el hueco se ve, no se interpola.
		series.extractingPath("$.item.buckets[?(@.year == 2024)].populationYear").asArray().containsOnly(2024);

		// Los ejes sin años no traen cobertura por año: no hay dos años que comparar.
		assertThat(mvc.get().uri("/api/v1/urban/aggregations").param("by", "district"))
				.hasStatusOk().bodyJson().extractingPath("$.item.coverageByYear").asArray().isEmpty();

		// --- API: resumen -----------------------------------------------------------------------------------
		var summary = assertThat(mvc.get().uri("/api/v1/urban/summary")).hasStatusOk().bodyJson();
		summary.extractingPath("$.item.premises").isEqualTo(500);
		summary.extractingPath("$.item.licences").isEqualTo((int) premises.countLicences());
		summary.extractingPath("$.item.earliestCreatedAt").isNotNull();
		summary.extractingPath("$.item.assignment.NO_POINT").isNotNull();
		// El estado se publica como código, sin traducir: no hay taxonomía (ADR-016 §6).
		summary.extractingPath("$.item.byStatusCode").asMap().isNotEmpty();

		// --- filtros que cruzan las dos tablas --------------------------------------------------------------
		Integer year = jdbc.sql("select year from urban_premises_licence group by year order by count(*) desc limit 1")
				.query(Integer.class).single();
		long withThatYear = jdbc.sql("""
				select count(distinct premises_id) from urban_premises_licence where year = ?
				""").param(year).query(Long.class).single();
		assertThat(mvc.get().uri("/api/v1/urban/premises").param("licenceYear", String.valueOf(year)))
				.hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo((int) withThatYear);
	}

	// --- ayudas -------------------------------------------------------------------------------------------

	/** Deja las 29 juntas con su geometría en la base de datos: sin ellas no hay a qué resolver. */
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
		// El padrón lo trae el listener de geo, que es asíncrono, y las agregaciones necesitan el denominador
		// (regla 7). Hacen falta las dos esperas y en este orden: `verify` garantiza que ya no llegarán más
		// peticiones al servidor simulado —sin eso, el `reset` de abajo pilla al listener a media faena—, y el
		// recuento garantiza que la escritura ha terminado. Ninguna sirve sola: el recuento puede estar ya en
		// 116 porque lo dejó otro test (la base de datos es la misma), y `verify` se satisface cuando se hizo
		// la última petición, no cuando se guardó su respuesta.
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> server.verify());
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc
				.sql("select count(*) from geo_population_record").query(Long.class).single()).isEqualTo(29L * 4));
		server.reset();
	}

	private void expectListingPage(String body) {
		server.expect(requestTo(Matchers.startsWith(LIST_URL))).andRespond(withSuccess(body, JSON_UTF8));
	}

	private IngestionJob job(DatasetRef dataset) {
		return jobs.stream().filter(j -> dataset.equals(j.source().dataset())).findFirst().orElseThrow();
	}

	private List<String> columnsOf(String table) {
		return jdbc.sql("select column_name from information_schema.columns where table_name = ?").param(table)
				.query(String.class).list();
	}

}
