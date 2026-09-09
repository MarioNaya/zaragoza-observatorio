package es.zaragoza.observatory.citizen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
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
import es.zaragoza.observatory.citizen.domain.AssignmentCounts;
import es.zaragoza.observatory.citizen.domain.ServiceRequest;
import es.zaragoza.observatory.citizen.domain.ServiceRequestQuery;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.geo.GeoSources;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.support.Fixtures;

/**
 * El módulo {@code citizen} de punta a punta con PostGIS real y la API municipal simulada con la página que
 * grabó S2.2: ingesta con la proyección de ADR-012, resolución territorial por {@code ST_Contains} contra las 29
 * juntas reales, upsert idempotente y la API de lectura.
 * <p>
 * Dos cosas se comprueban aquí porque solo aquí se pueden comprobar: que <b>ninguna columna de la base de datos
 * contiene texto de la queja</b> (ADR-012 de verdad, no en el papel) y que las agregaciones y sus filtros
 * funcionan sobre PostgreSQL, que es donde vive el SQL del adaptador.
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S", "zaragoza.catalog.observation.enabled=false",
		"zaragoza.geo.profile-request-delay=PT0S" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@AutoConfigureMockMvc
class CitizenIntegrationTests {

	static final String LIST_URL = "https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json";
	static final String DISTRICTS_URL = "https://www.zaragoza.es/sede/servicio/distrito.json";
	static final String DETAIL_PREFIX = "https://www.zaragoza.es/sede/servicio/distrito/";
	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	/** Palabras que aparecerían si el texto libre hubiera entrado alguna vez (ADR-012). */
	static final List<String> TEXT_MARKERS = List.of("atentamente", "buenos d", "solicito", "les escribo",
			"un saludo");

	@Autowired
	Ingestion ingestion;

	@Autowired
	ObjectProvider<IngestionJob> jobs;

	@Autowired
	ServiceRequestRepository requests;

	@Autowired
	MockRestServiceServer server;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void ingestsComplaintsResolvesTheirDistrictAndServesTheApi() {
		loadDistricts();

		// --- ingesta de altas: proyección sin texto y paginación por offset ---------------------------------
		IngestionJob newRequests = job(CitizenSources.REQUESTS);
		expectListingPage(Fixtures.text("open311/sede-list-ingest-page.json"));
		expectListingPage("[]");
		IngestionRunSummary run = ingestion.run(newRequests);

		assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(run.records()).isEqualTo(500);
		assertThat(requests.count()).isEqualTo(500);

		// --- ADR-012: no hay texto de la queja en ninguna columna -------------------------------------------
		assertThat(columnsOf("citizen_service_request"))
				.doesNotContain("title", "description", "service_notice", "address_string");
		for (String marker : TEXT_MARKERS) {
			assertThat(rowsContaining(marker)).as("ninguna fila puede contener '%s'", marker).isZero();
		}
		// Ni siquiera en la página cruda que guarda `ingestion`: no se pidió, así que no llegó.
		assertThat(jdbc.sql("""
				select count(*) from raw_payload
				where source = 'sede' and dataset_id = 'quejas-sugerencias'
				  and (body ilike '%"description"%' or body ilike '%"title"%'
				       or body ilike '%"service_notice"%' or body ilike '%"address_string"%')
				""").query(Long.class).single()).isZero();

		// --- ADR-011: territorio resuelto por geometría, declarado aparte -----------------------------------
		AssignmentCounts counts = requests.assignmentCounts(ServiceRequestQuery.all());
		assertThat(counts.total()).isEqualTo(500);
		assertThat(counts.byAssignment().get(Assignment.RESOLVED)).isPositive();
		assertThat(counts.byAssignment().get(Assignment.NO_POINT)).isPositive();
		assertThat(counts.unassigned()).isPositive();
		assertThat(counts.declaredAgrees()).as("la mayoría de los declarados coinciden con la geometría")
				.isGreaterThan(counts.declaredDisagrees());

		// Un registro sin punto nunca tiene junta, aunque el origen declare una (ADR-011 §2).
		assertThat(jdbc.sql("""
				select count(*) from citizen_service_request
				where lon is null and district_id is not null
				""").query(Long.class).single()).isZero();
		// Y el declarado que no casa con ninguna junta se conserva tal cual, sin id.
		assertThat(jdbc.sql("""
				select count(*) from citizen_service_request
				where district_declared is not null and district_declared_id is null
				""").query(Long.class).single()).isNotNull();

		// La junta resuelta existe de verdad entre las 29 (no hay clave ajena entre módulos: se comprueba aquí).
		assertThat(jdbc.sql("""
				select count(*) from citizen_service_request c
				where c.district_id is not null
				  and not exists (select 1 from geo_district d where d.id = c.district_id)
				""").query(Long.class).single()).isZero();

		// --- marcas de agua y segunda ingesta idempotente ---------------------------------------------------
		Instant firstWatermark = requests.latestRequestedAt().orElseThrow();
		assertThat(requests.latestUpdatedAt()).isPresent();
		assertThat(requests.earliestRequestedAt()).get().isNotNull();

		ServiceRequest before = requests.search(ServiceRequestQuery.all()).items().get(0);
		server.reset();
		expectListingPage(Fixtures.text("open311/sede-list-ingest-page.json"));
		expectListingPage("[]");
		assertThat(ingestion.run(job(CitizenSources.REQUESTS)).status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(requests.count()).as("upsert idempotente por service_request_id (regla 5)").isEqualTo(500);
		ServiceRequest after = requests.search(ServiceRequestQuery.all()).items().get(0);
		assertThat(after.firstSeenAt()).isEqualTo(before.firstSeenAt());
		assertThat(after.lastSeenAt()).isAfterOrEqualTo(before.lastSeenAt());
		assertThat(requests.latestRequestedAt()).contains(firstWatermark);

		// La segunda ejecución ya lleva marca de agua: pide solo lo nuevo (S2.2).
		assertThat(job(CitizenSources.REQUESTS).source().query()).containsKey("q");

		// --- API: listado ----------------------------------------------------------------------------------
		var listing = assertThat(mvc.get().uri("/api/v1/citizen/requests").param("size", "5"))
				.hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON).bodyJson();
		listing.extractingPath("$.total").isEqualTo(500);
		listing.extractingPath("$.items").asArray().hasSize(5);
		listing.extractingPath("$.source.dataset").isEqualTo("sede:quejas-sugerencias");
		listing.extractingPath("$.caveats").asArray().isNotEmpty();
		listing.extractingPath("$.items[0].status").isNotNull();
		// El DTO no tiene ningún campo de texto de la queja.
		listing.extractingPath("$.items[0]").asMap()
				.doesNotContainKeys("title", "description", "serviceNotice", "addressText");

		assertThat(mvc.get().uri("/api/v1/citizen/requests").param("assignment", "no_point"))
				.hasStatusOk().bodyJson().extractingPath("$.items[*].districtId").asArray()
				.allSatisfy(id -> assertThat(id).isNull());
		assertThat(mvc.get().uri("/api/v1/citizen/requests").param("status", "closed").param("size", "3"))
				.hasStatusOk().bodyJson().extractingPath("$.items[*].status").asArray().containsOnly("CLOSED");
		assertThat(mvc.get().uri("/api/v1/citizen/requests").param("sort", "inventado,asc"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/citizen/requests").param("status", "inventado"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/citizen/requests").param("size", "5000"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- API: agregaciones con denominador y sin asignar ------------------------------------------------
		var byDistrict = assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "district"))
				.hasStatusOk().bodyJson();
		byDistrict.extractingPath("$.item.by").isEqualTo("district");
		byDistrict.extractingPath("$.item.buckets").asArray().isNotEmpty();
		byDistrict.extractingPath("$.item.buckets[0].population").isNotNull();
		byDistrict.extractingPath("$.item.buckets[0].populationYear").isEqualTo(2024);
		byDistrict.extractingPath("$.item.buckets[0].perThousandInhabitants").isNotNull();
		byDistrict.extractingPath("$.item.buckets[0].pointCoverage").isEqualTo(1.0);
		byDistrict.extractingPath("$.item.buckets[0].label").isNotNull();
		byDistrict.extractingPath("$.item.unassigned").isNotNull();
		byDistrict.extractingPath("$.item.assignment.byAssignment.NO_POINT").isNotNull();

		var byCategory = assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "category"))
				.hasStatusOk().bodyJson();
		byCategory.extractingPath("$.item.buckets[0].label").isNotNull();
		byCategory.extractingPath("$.item.matched").isEqualTo(500);

		var byMonth = assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "month"))
				.hasStatusOk().bodyJson();
		byMonth.extractingPath("$.item.buckets[0].key").asString().matches("\\d{4}-\\d{2}");
		assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "barrio"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- API: resumen -----------------------------------------------------------------------------------
		var summary = assertThat(mvc.get().uri("/api/v1/citizen/summary")).hasStatusOk().bodyJson();
		summary.extractingPath("$.item.total").isEqualTo(500);
		summary.extractingPath("$.item.byStatus.CLOSED").isNotNull();
		summary.extractingPath("$.item.earliestRequestedAt").isNotNull();
		summary.extractingPath("$.item.assignment.declaredUnmatched").isNotNull();

		// --- ADR-015: los INTERNAL cuentan, se ven y se pueden quitar ---------------------------------------
		// La página de S2.2 trae 11 servicios INTERNAL entre los 500, todos con service_code 2.
		long internalRows = jdbc.sql("select count(*) from citizen_service_request where service_code = '2'")
				.query(Long.class).single();
		assertThat(internalRows).isEqualTo(11);
		assertThat(jdbc.sql("""
				select count(distinct service_name) from citizen_service_request where service_code = '2'
				""").query(Long.class).single()).isEqualTo(1);

		summary.extractingPath("$.item.internal").isEqualTo(11);

		var withInternal = assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "category"))
				.hasStatusOk().bodyJson();
		withInternal.extractingPath("$.item.matched").isEqualTo(500);
		withInternal.extractingPath("$.item.internal").isEqualTo(11);

		var withoutInternal = assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "category")
				.param("internal", "exclude")).hasStatusOk().bodyJson();
		withoutInternal.extractingPath("$.item.matched").isEqualTo(489);
		withoutInternal.extractingPath("$.item.internal").isEqualTo(0);

		var onlyInternal = assertThat(mvc.get().uri("/api/v1/citizen/requests").param("internal", "only")
				.param("size", "50")).hasStatusOk().bodyJson();
		onlyInternal.extractingPath("$.total").isEqualTo(11);
		onlyInternal.extractingPath("$.items[*].serviceName").asArray().containsOnly("INTERNAL");

		assertThat(mvc.get().uri("/api/v1/citizen/requests").param("internal", "exclude"))
				.hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(489);
		assertThat(mvc.get().uri("/api/v1/citizen/requests").param("internal", "inventado"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- ADR-015: la serie por junta y año, con el padrón de su propio año -------------------------------
		var byYear = assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "district_year"))
				.hasStatusOk().bodyJson();
		byYear.extractingPath("$.item.by").isEqualTo("district_year");
		byYear.extractingPath("$.item.buckets").asArray().isNotEmpty();
		byYear.extractingPath("$.item.buckets[0].year").isEqualTo(2026);
		byYear.extractingPath("$.item.buckets[0].pointCoverage").isNotNull();
		// 2026 no tiene padrón publicado (la serie es 2020, 2021, 2022 y 2024): sin denominador y se ve.
		byYear.extractingPath("$.item.buckets[0].population").isNull();
		byYear.extractingPath("$.item.buckets[0].perThousandInhabitants").isNull();
		// Y el caveat que lo explica viaja con la respuesta (regla 7).
		byYear.extractingPath("$.caveats").asArray()
				.anySatisfy(caveat -> assertThat(caveat.toString()).contains("padrón"));

		// La cobertura del año va aparte y cuenta TODAS las quejas, no solo las situadas: dentro de un grupo por
		// junta la cobertura es siempre 1 por construcción, y eso no dice nada (ADR-015).
		byYear.extractingPath("$.item.buckets[*].pointCoverage").asArray().containsOnly(1.0);
		byYear.extractingPath("$.item.coverageByYear").asArray().hasSize(1);
		byYear.extractingPath("$.item.coverageByYear[0].year").isEqualTo(2026);
		byYear.extractingPath("$.item.coverageByYear[0].total").isEqualTo(500);
		byYear.extractingPath("$.item.coverageByYear[0].withPoint").isEqualTo(255);
		byYear.extractingPath("$.item.coverageByYear[0].assigned").isEqualTo(255);
		byYear.extractingPath("$.item.coverageByYear[0].pointCoverage").isEqualTo(0.51);
		// Los demás ejes no la traen: no hay dos años que comparar.
		assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "district"))
				.hasStatusOk().bodyJson().extractingPath("$.item.coverageByYear").asArray().isEmpty();

		// La página grabada es la más reciente, así que todo cae en un año. Se mueven a 2024 los registros de
		// una junta para comprobar lo que hace la serie cuando el año sí tiene padrón. Es manipulación del
		// fixture, declarada: la fuente real sí tiene ambos años (S2.2).
		Integer districtWithRows = jdbc.sql("""
				select district_id from citizen_service_request
				where district_id is not null group by district_id order by count(*) desc limit 1
				""").query(Integer.class).single();
		int moved = jdbc.sql("""
				update citizen_service_request set requested_at = requested_at - interval '2 years'
				where district_id = ?
				""").param(districtWithRows).update();
		assertThat(moved).isPositive();

		var twoYears = assertThat(mvc.get().uri("/api/v1/citizen/aggregations").param("by", "district_year"))
				.hasStatusOk().bodyJson();
		twoYears.extractingPath("$.item.buckets[?(@.year == 2024)]").asArray().isNotEmpty();
		twoYears.extractingPath("$.item.buckets[?(@.year == 2024)].population").asArray()
				.allSatisfy(population -> assertThat(population).isNotNull());
		twoYears.extractingPath("$.item.buckets[?(@.year == 2024)].populationYear").asArray().containsOnly(2024);
		twoYears.extractingPath("$.item.buckets[?(@.year == 2024)].perThousandInhabitants").asArray()
				.allSatisfy(rate -> assertThat(rate).isNotNull());
		// El total del cruce sigue siendo el de la agregación por junta: no se pierde ni se duplica nada.
		long territorial = jdbc.sql("select count(*) from citizen_service_request where district_id is not null")
				.query(Long.class).single();
		twoYears.extractingPath("$.item.matched").isEqualTo((int) territorial);
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
		return jdbc.sql("select column_name from information_schema.columns where table_name = ?")
				.param(table).query(String.class).list();
	}

	private long rowsContaining(String marker) {
		return jdbc.sql("""
				select count(*) from citizen_service_request
				where service_name ilike ? or service_code ilike ? or district_declared ilike ?
				""").params(List.of("%" + marker + "%", "%" + marker + "%", "%" + marker + "%"))
				.query(Long.class).single();
	}

}
