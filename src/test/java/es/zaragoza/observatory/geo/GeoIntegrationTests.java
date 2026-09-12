package es.zaragoza.observatory.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import es.zaragoza.observatory.geo.DistrictLocation;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictKind;
import es.zaragoza.observatory.geo.domain.DistrictLocator;
import es.zaragoza.observatory.geo.domain.DistrictRepository;
import es.zaragoza.observatory.geo.domain.PopulationRepository;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El módulo {@code geo} de punta a punta con PostGIS real y la API municipal simulada con las respuestas
 * grabadas por S2.1: ingesta de las 29 juntas con su geometría, lectura del detalle de cada una por el listener
 * y —lo importante— <b>la comprobación de aceptación de ADR-011</b>: repetir con {@code ST_Contains} la
 * comparación que hizo el spike contra la junta oficial de {@code locales-vacios} y obtener lo mismo.
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S", "zaragoza.catalog.observation.enabled=false",
		"zaragoza.geo.profile-request-delay=PT0S" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@AutoConfigureMockMvc
class GeoIntegrationTests {

	static final String DISTRICTS_URL = "https://www.zaragoza.es/sede/servicio/distrito.json";
	static final String DETAIL_PREFIX = "https://www.zaragoza.es/sede/servicio/distrito/";
	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	/**
	 * Acuerdo mínimo con la junta oficial. El spike midió 99,69 % sobre las 3.824 fichas completas y 98,3 %
	 * sobre esta página, que es donde se concentran las cuatro discrepancias de borde (S2.1).
	 */
	static final double MIN_AGREEMENT = 0.98;

	@Autowired
	Ingestion ingestion;

	@Autowired
	ObjectProvider<IngestionJob> jobs;

	@Autowired
	DistrictRepository districts;

	@Autowired
	PopulationRepository population;

	@Autowired
	DistrictLocator locator;

	@Autowired
	MockRestServiceServer server;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	final JsonMapper json = JsonMapper.shared();

	@Test
	void ingestsTheDistrictsResolvesPointsAndServesTheApi() {
		IngestionJob job = districtsJob();
		assertThat(job.source().url()).hasToString(DISTRICTS_URL);
		assertThat(job.source().query()).containsEntry("srsname", "wgs84");
		assertThat(job.source().shape()).isEqualTo(ResponseShape.ENVELOPE);
		assertThat(job.interval()).isEqualTo(Duration.ofDays(1));

		// --- capa base: una petición, 29 juntas con geometría -----------------------------------------------
		expectDistrictListing();
		expectDistrictDetails();
		IngestionRunSummary first = ingestion.run(job);
		assertThat(first.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(first.records()).isEqualTo(29);
		assertThat(districts.count()).isEqualTo(29);

		District rabal = districts.findById(6).orElseThrow();
		assertThat(rabal.name()).isEqualTo("Junta Municipal El Rabal");
		assertThat(rabal.kind()).isEqualTo(DistrictKind.MUNICIPAL);
		assertThat(districts.findAll().stream().map(District::id)).isSorted();
		assertThat(jdbc.sql("select count(*) from geo_district where ST_IsValid(boundary)").query(Long.class).single())
				.isEqualTo(29L);
		assertThat(jdbc.sql("select ST_SRID(boundary) from geo_district where id = 6").query(Integer.class).single())
				.isEqualTo(4326);

		// --- el listener lee el detalle de cada junta: idpadron y padrón ------------------------------------
		await().atMost(Duration.ofSeconds(30))
				.untilAsserted(() -> assertThat(districts.findById(6).orElseThrow().padronId()).isEqualTo(15));
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(population.count()).isEqualTo(29L * 4));
		assertThat(population.years()).containsExactly(2024, 2022, 2021, 2020);
		assertThat(population.findLatest(6)).get().satisfies(latest -> {
			assertThat(latest.year()).isEqualTo(2024);
			assertThat(latest.total()).isEqualTo(78438);
			assertThat(latest.areaKm2()).isEqualTo(8.400154);
		});
		// Las dos numeraciones no coinciden: es justamente por lo que se guardan las dos (S2.1).
		assertThat(districts.findAll().stream().filter(d -> d.padronId() != null && d.padronId() != d.id()))
				.isNotEmpty();

		// --- ADR-011: ST_Contains contra la junta oficial de locales-vacios ---------------------------------
		// La comprobación de aceptación: PostGIS tiene que dar exactamente lo que midió el spike sobre esta
		// misma página (S2.1, tabla «señal territorial»): 233 de 237 registros comparables, y las 4
		// discrepancias son el mismo tramo del borde Delicias / La Almozara, no ruido repartido.
		var expected = officialAssignments();
		assertThat(expected).as("registros de la página con punto y junta oficial").hasSize(237);
		List<DistrictLocation> located = locator.locateAll(expected.stream().map(Assignment::point).toList());
		int agree = 0, outside = 0, ambiguous = 0;
		var disagreements = new ArrayList<String>();
		for (int i = 0; i < expected.size(); i++) {
			DistrictLocation location = located.get(i);
			if (!location.isResolved()) {
				outside++;
				continue;
			}
			if (location.isAmbiguous()) {
				ambiguous++;
			}
			if (location.candidates().contains(expected.get(i).districtId())) {
				agree++;
			}
			else {
				disagreements.add(expected.get(i).districtId() + "->" + location.districtId());
			}
		}
		assertThat(agree).as("acuerdo con la junta oficial (S2.1: 233 de 237 en esta página)").isEqualTo(233);
		assertThat((double) agree / expected.size()).isGreaterThanOrEqualTo(MIN_AGREEMENT);
		assertThat(outside).as("ningún punto de la muestra cae fuera de las 29 juntas (S2.1)").isZero();
		assertThat(ambiguous).as("ningún punto de la muestra cae en dos juntas (S2.1)").isZero();
		assertThat(disagreements).as("las 4 discrepancias son el borde Delicias (5) / La Almozara (2)")
				.containsOnly("5->2").hasSize(4);

		// --- resolución puntual: dentro, fuera y punto inválido ---------------------------------------------
		DistrictLocation inside = locator.locate(expected.get(0).point());
		assertThat(inside.status()).isEqualTo(DistrictLocation.Status.RESOLVED);
		assertThat(inside.districtId()).isEqualTo(expected.get(0).districtId());
		assertThat(locator.locate(new GeoPoint(-3.7038, 40.4168)).status()) // Madrid
				.isEqualTo(DistrictLocation.Status.OUTSIDE);
		assertThat(locator.locateAll(List.of())).isEmpty();

		// Los 29 polígonos publicados no son una partición: este punto del norte rural cae a la vez en Alfocea
		// (14) y Juslibol (18), S2.1. Se elige la de menor id y se conservan las dos candidatas (ADR-011).
		DistrictLocation overlap = locator.locate(new GeoPoint(-0.99088949, 41.73899086));
		assertThat(overlap.status()).isEqualTo(DistrictLocation.Status.AMBIGUOUS);
		assertThat(overlap.districtId()).isEqualTo(14);
		assertThat(overlap.candidates()).containsExactly(14, 18);

		// --- segunda ingesta: idempotente (regla 5) y sin perder el idpadron --------------------------------
		server.reset();
		expectDistrictListing();
		expectDistrictDetails();
		assertThat(ingestion.run(job).status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(districts.count()).isEqualTo(29);
		District rabalAgain = districts.findById(6).orElseThrow();
		assertThat(rabalAgain.padronId()).as("el listado no trae idpadron y no debe borrarlo").isEqualTo(15);
		assertThat(rabalAgain.firstSeenAt()).isEqualTo(rabal.firstSeenAt());
		assertThat(rabalAgain.lastSeenAt()).isAfterOrEqualTo(rabal.lastSeenAt());
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(population.count()).isEqualTo(29L * 4));

		// --- API -------------------------------------------------------------------------------------------
		var listing = assertThat(mvc.get().uri("/api/v1/geo/districts")).hasStatusOk()
				.hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON).bodyJson();
		listing.extractingPath("$.count").isEqualTo(29);
		listing.extractingPath("$.items[0].id").isEqualTo(1);
		listing.extractingPath("$.items[0].kind").isEqualTo("MUNICIPAL");
		listing.extractingPath("$.source.dataset").isEqualTo("sede:distrito");
		listing.extractingPath("$.caveats").asArray().isNotEmpty();
		assertThat(mvc.get().uri("/api/v1/geo/districts").param("sort", "padronId,desc"))
				.hasStatusOk().bodyJson().extractingPath("$.items[0].padronId").isEqualTo(129);
		assertThat(mvc.get().uri("/api/v1/geo/districts").param("kind", "VECINAL"))
				.hasStatusOk().bodyJson().extractingPath("$.count").isEqualTo(14);
		assertThat(mvc.get().uri("/api/v1/geo/districts").param("sort", "inventado,asc"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		var detail = assertThat(mvc.get().uri("/api/v1/geo/districts/6")).hasStatusOk().bodyJson();
		detail.extractingPath("$.item.district.shortName").isEqualTo("El Rabal");
		detail.extractingPath("$.item.district.padronId").isEqualTo(15);
		detail.extractingPath("$.item.district.latestPopulation.year").isEqualTo(2024);
		detail.extractingPath("$.item.population[*].year").asArray().containsExactly(2024, 2022, 2021, 2020);
		assertThat(mvc.get().uri("/api/v1/geo/districts/9999")).hasStatus(HttpStatus.NOT_FOUND);

		GeoPoint sample = expected.get(0).point();
		var resolved = assertThat(mvc.get().uri("/api/v1/geo/locate")
				.param("lon", Double.toString(sample.lon())).param("lat", Double.toString(sample.lat())))
				.hasStatusOk().bodyJson();
		resolved.extractingPath("$.item.status").isEqualTo("RESOLVED");
		resolved.extractingPath("$.item.districtId").isEqualTo(expected.get(0).districtId());
		resolved.extractingPath("$.item.districtName").isNotNull();
		assertThat(mvc.get().uri("/api/v1/geo/locate").param("lon", "-3.7038").param("lat", "40.4168"))
				.hasStatusOk().bodyJson().extractingPath("$.item.status").isEqualTo("OUTSIDE");
		var overlapping = assertThat(mvc.get().uri("/api/v1/geo/locate")
				.param("lon", "-0.99088949").param("lat", "41.73899086")).hasStatusOk().bodyJson();
		overlapping.extractingPath("$.item.status").isEqualTo("AMBIGUOUS");
		overlapping.extractingPath("$.item.candidates").asArray().containsExactly(14, 18);
		assertThat(mvc.get().uri("/api/v1/geo/locate").param("lon", "999").param("lat", "0"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		assertBoundariesAreDrawableAndMatchTheRows();
	}

	/**
	 * Los contornos que pinta el mapa (ADR-020 §4). Lo que se comprueba no es que la respuesta exista sino que
	 * es GeoJSON de verdad —geometría como objeto y no como texto escapado— y que su {@code id} es el mismo con
	 * el que vienen las filas del cruce: si no lo fuera, el mapa pintaría bien y casaría mal, que es peor.
	 */
	private void assertBoundariesAreDrawableAndMatchTheRows() {
		var geojson = assertThat(mvc.get().uri("/api/v1/geo/boundaries")).hasStatusOk()
				.hasContentTypeCompatibleWith(MediaType.parseMediaType("application/geo+json")).bodyJson();
		geojson.extractingPath("$.type").isEqualTo("FeatureCollection");
		geojson.extractingPath("$.count").isEqualTo(29);
		geojson.extractingPath("$.source.dataset").isEqualTo("sede:distrito");
		geojson.extractingPath("$.features[0].type").isEqualTo("Feature");
		geojson.extractingPath("$.features[0].id").isEqualTo(1);
		geojson.extractingPath("$.features[0].properties.shortName").isNotNull();
		// La geometría es un objeto GeoJSON, no una cadena: @JsonRawValue la inserta sin escapar.
		geojson.extractingPath("$.features[0].geometry.type").isEqualTo("Polygon");
		geojson.extractingPath("$.features[0].geometry.coordinates[0][0]").asArray().hasSize(2);

		// Las 29 juntas del listado están todas, y con el mismo id: el mapa casa con el cruce por esa clave.
		JsonNode body = JsonMapper.shared()
				.readTree(mvc.get().uri("/api/v1/geo/boundaries").exchange().getResponse().getContentAsByteArray());
		var drawn = new ArrayList<Integer>();
		body.path("features").forEach(feature -> drawn.add(feature.path("id").asInt()));
		assertThat(drawn).as("un contorno por junta, por el id con el que se cruzan las filas")
				.containsExactlyElementsOf(districts.findAll().stream().map(District::id).toList());
	}

	// --- ayudas -------------------------------------------------------------------------------------------

	/** Una ficha de {@code locales-vacios} con punto y con la junta que le asigna el ayuntamiento (S2.1). */
	record Assignment(GeoPoint point, int districtId) {
	}

	private List<Assignment> officialAssignments() {
		JsonNode result = json.readTree(Fixtures.text("geo/locales-vacios-junta-punto-page0.json")).path("result");
		List<Assignment> assignments = new ArrayList<>();
		for (JsonNode local : result) {
			JsonNode geometry = local.path("geometry");
			int districtId = local.path("portal").path("junta").path("id").asInt(-1);
			if (districtId <= 0 || !geometry.path("coordinates").isArray()) {
				continue;
			}
			JsonNode coordinates = geometry.path("coordinates");
			assignments.add(new Assignment(
					new GeoPoint(coordinates.get(0).asDouble(), coordinates.get(1).asDouble()), districtId));
		}
		return assignments;
	}

	private IngestionJob districtsJob() {
		return jobs.stream().filter(j -> GeoSources.DISTRICTS.equals(j.source().dataset())).findFirst().orElseThrow();
	}

	private void expectDistrictListing() {
		server.expect(requestTo(Matchers.startsWith(DISTRICTS_URL)))
				.andRespond(withSuccess(Fixtures.text("geo/distrito.json_srsname-wgs84_rows-100"), JSON_UTF8));
	}

	/**
	 * Las 29 peticiones de detalle. Se responde con la respuesta real de El Rabal reescribiendo el id: el lector
	 * comprueba que {@code iddatosab} coincide con la junta pedida (S2.1), así que un cuerpo fijo no serviría, y
	 * grabar 29 fixtures del mismo endpoint sería ruido. El {@code idpadron} se desplaza igual que el id para
	 * comprobar que se guardan dos numeraciones distintas.
	 */
	private void expectDistrictDetails() {
		String template = Fixtures.text("geo/distrito-6-indicadores.json");
		server.expect(ExpectedCount.times(29), requestTo(Matchers.startsWith(DETAIL_PREFIX)))
				.andRespond(request -> {
					String path = request.getURI().getPath();
					String id = path.substring(path.lastIndexOf('/') + 1).replace(".json", "");
					int districtId = Integer.parseInt(id);
					String body = template.replace("{\"id\":6,", "{\"id\":" + districtId + ",")
							.replace("\"iddatosab\":6", "\"iddatosab\":" + districtId)
							.replace("\"idpadron\":15", "\"idpadron\":" + padronIdFor(districtId));
					return withSuccess(body, JSON_UTF8).createResponse(request);
				});
	}

	/** Correspondencia de juguete, pero distinta del id: lo que se comprueba es que son dos números. */
	private static int padronIdFor(int districtId) {
		return Map.of(1, 14, 6, 15, 30, 29).getOrDefault(districtId, districtId + 100);
	}

}
