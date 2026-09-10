package es.zaragoza.observatory.spending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
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
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.spending.application.ReadBudgetSnapshots;
import es.zaragoza.observatory.spending.domain.BudgetRepository;

/**
 * El presupuesto de gastos de punta a punta con PostgreSQL real y la API municipal simulada con los documentos
 * que grabó S3.2: censo de instantáneas, lectura por lotes con sus desenlaces y la API de lectura.
 * <p>
 * Cuatro cosas se comprueban aquí porque solo aquí se pueden comprobar:
 * <ul>
 * <li>que el barrido de una instantánea manda siempre <b>{@code sort=id asc}</b>, que es la defensa entera
 * contra el orden por defecto de esta fuente (S3.2 §2);</li>
 * <li>que una instantánea cargada que no es la más reciente <b>se congela</b>, y por eso el histórico se paga
 * una sola vez (S3.2 §3);</li>
 * <li>que el nombre de una partida que nombra a una persona física no llega a la base de datos, y que la base de
 * datos lo rechazaría aunque el traductor cambiara (S3.2 §8, regla 22);</li>
 * <li>que las agregaciones y la serie por año funcionan sobre PostgreSQL, que es donde vive el SQL.</li>
 * </ul>
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S", "zaragoza.catalog.observation.enabled=false",
		"zaragoza.spending.releases.enabled=false", "zaragoza.spending.budget.enabled=false",
		"zaragoza.spending.budget.request-delay=PT0S" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@AutoConfigureMockMvc
class BudgetIntegrationTests {

	static final String BASE = "https://www.zaragoza.es/sede/servicio/presupuesto/gasto-corriente";

	static final String CENSUS_URL = BASE + "/fecha.json";

	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	/** El cierre de un año antiguo: sin programa presupuestario y con una partida que nombra a una persona. */
	static final LocalDate OLD = LocalDate.of(2006, 12, 31);

	/** El cierre de un año cerrado. */
	static final LocalDate CLOSED = LocalDate.of(2025, 12, 31);

	/** La más reciente: la única que se relee, y la que contestan por defecto listado y agregaciones. */
	static final LocalDate LATEST = LocalDate.of(2026, 8, 31);

	/** Censada y sin contenido: su URL responde 404. */
	static final LocalDate MISSING = LocalDate.of(2026, 7, 31);

	static final List<LocalDate> ALL = List.of(OLD, CLOSED, MISSING, LATEST);

	@Autowired
	Ingestion ingestion;

	@Autowired
	ObjectProvider<IngestionJob> jobs;

	@Autowired
	BudgetRepository budget;

	@Autowired
	ReadBudgetSnapshots readSnapshots;

	@Autowired
	MockRestServiceServer server;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void censaLasInstantaneasLeeSusPartidasYSirveLaApi() {
		// --- censo: una petición, y lo que trae son URL ------------------------------------------------------
		expectCensus();
		IngestionRunSummary run = ingestion.run(job(SpendingSources.BUDGET));

		assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(run.records()).isEqualTo(ALL.size());
		assertThat(jdbc.sql("select count(*) from spending_budget_snapshot").query(Long.class).single())
				.isEqualTo(ALL.size());
		assertThat(jdbc.sql("select count(*) from spending_budget_snapshot where read_status = 'PENDING'")
				.query(Long.class).single()).isEqualTo(ALL.size());
		// La página cruda del censo sí se guarda: son URL y nada más.
		assertThat(jdbc.sql("""
				select count(*) from raw_payload where source = 'sede' and dataset_id = 'presupuesto/gasto-corriente'
				""").query(Long.class).single()).isPositive();
		server.reset();

		// --- lectura por lotes ------------------------------------------------------------------------------
		expectSnapshots();
		var batch = readSnapshots.readDue(10);

		assertThat(batch.requested()).isEqualTo(4);
		assertThat(batch.loaded()).isEqualTo(3);
		assertThat(batch.absent()).isEqualTo(1);
		assertThat(batch.unreadable()).isZero();
		assertThat(batch.lines()).isEqualTo(6);
		server.verify();

		assertThat(status(LATEST)).isEqualTo("LOADED");
		assertThat(status(MISSING)).as("un 404 no borra la instantánea: la fecha existe en el censo")
				.isEqualTo("ABSENT");

		// S3.2 §3: lo cargado que no es lo último se congela; la más reciente conserva cadencia.
		assertThat(nextAttempt(OLD)).as("congelada: no se vuelve a pedir nunca").isNull();
		assertThat(nextAttempt(CLOSED)).isNull();
		assertThat(nextAttempt(LATEST)).as("la única que se relee").isNotNull();
		assertThat(nextAttempt(MISSING)).as("y la ausente se reintenta con espera creciente").isNotNull();

		// --- regla 22: el nombre que nombra a una persona no llega a la base de datos -----------------------
		assertThat(jdbc.sql("""
				select count(*) from spending_budget_line where heading_redacted and heading is null
				""").query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from spending_budget_line where heading ilike '%viuda%'")
				.query(Long.class).single()).isZero();
		// Y la base de datos lo rechaza aunque alguien cambiara el traductor.
		assertThatThrownBy(() -> jdbc.sql("""
				insert into spending_budget_line (snapshot_date, concept, heading, heading_redacted)
				values (?, 'inventado', 'A LA VIUDA DE ALGUIEN', true)
				""").param(OLD).update())
				.as("la mitad de la garantía que vive en la base de datos (S3.2 §8)")
				.hasMessageContaining("spending_budget_line_redacted_has_no_text");

		// --- el modelo: totales materializados y clasificación que no existía -------------------------------
		assertThat(jdbc.sql("select obligations from spending_budget_snapshot where snapshot_date = ?")
				.param(LATEST).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("28728.61");
		assertThat(jdbc.sql("""
				select count(*) from spending_budget_line where snapshot_date = ? and programme_id is null
				""").param(OLD).query(Long.class).single()).isEqualTo(1);

		// --- segunda lectura: idempotente, sin acumular partidas --------------------------------------------
		jdbc.sql("update spending_budget_snapshot set next_attempt_at = now() - interval '1 day'").update();
		server.reset();
		expectSnapshots();
		readSnapshots.readDue(10);

		assertThat(jdbc.sql("select count(*) from spending_budget_line").query(Long.class).single())
				.as("las partidas se reemplazan, no se acumulan").isEqualTo(6);

		// --- API: el censo y una instantánea ----------------------------------------------------------------
		var snapshots = assertThat(mvc.get().uri("/api/v1/spending/budget/snapshots")).hasStatusOk()
				.hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON).bodyJson();
		snapshots.extractingPath("$.total").isEqualTo(4);
		snapshots.extractingPath("$.source.dataset").isEqualTo("sede:presupuesto/gasto-corriente");
		snapshots.extractingPath("$.items[0].date").isEqualTo("2026-08-31");
		snapshots.extractingPath("$.items[0].frozen").isEqualTo(false);
		// La ausencia de territorio se dice, no se deja notar.
		snapshots.extractingPath("$.caveats").asArray()
				.anySatisfy(caveat -> assertThat(caveat.toString()).contains("junta"));

		var one = assertThat(mvc.get().uri("/api/v1/spending/budget/snapshots/2006-12-31")).hasStatusOk().bodyJson();
		one.extractingPath("$.item.readStatus").isEqualTo("LOADED");
		one.extractingPath("$.item.frozen").isEqualTo(true);
		one.extractingPath("$.item.amounts.obligations").isNotNull();
		assertThat(mvc.get().uri("/api/v1/spending/budget/snapshots/1999-12-31")).hasStatus(HttpStatus.NOT_FOUND);

		// --- API: partidas de UNA instantánea ---------------------------------------------------------------
		var lines = assertThat(mvc.get().uri("/api/v1/spending/budget/lines")).hasStatusOk().bodyJson();
		lines.extractingPath("$.snapshotDate").as("sin fecha se contesta la más reciente cargada")
				.isEqualTo("2026-08-31");
		lines.extractingPath("$.total").isEqualTo(2);
		lines.extractingPath("$.items[0].amounts.obligations").isNotNull();
		lines.extractingPath("$.items[0]").asMap().doesNotContainKeys("amount", "spent", "gasto");

		var oldLines = assertThat(mvc.get().uri("/api/v1/spending/budget/lines").param("date", "2006-12-31"))
				.hasStatusOk().bodyJson();
		oldLines.extractingPath("$.total").isEqualTo(2);
		oldLines.extractingPath("$.items[*].headingRedacted").asArray().contains(true);

		assertThat(mvc.get().uri("/api/v1/spending/budget/lines").param("chapter", "2")).hasStatusOk().bodyJson()
				.extractingPath("$.total").isEqualTo(1);
		assertThat(mvc.get().uri("/api/v1/spending/budget/lines").param("q", "regeneracion")).hasStatusOk().bodyJson()
				.extractingPath("$.total").isEqualTo(1);
		assertThat(mvc.get().uri("/api/v1/spending/budget/lines").param("sort", "inventado,asc"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- API: agregaciones ------------------------------------------------------------------------------
		var byYear = assertThat(mvc.get().uri("/api/v1/spending/budget/aggregations").param("by", "year"))
				.hasStatusOk().bodyJson();
		byYear.extractingPath("$.item.basis").isEqualTo("closing-snapshot-per-year");
		byYear.extractingPath("$.item.unit").isEqualTo("lines");
		byYear.extractingPath("$.item.items[*].key").asArray().containsExactly("2006", "2025", "2026");
		// Cada grupo dice de qué foto sale: el último año está a medio ejercicio.
		byYear.extractingPath("$.item.items[2].snapshotDate").isEqualTo("2026-08-31");
		byYear.extractingPath("$.caveats").asArray()
				.anySatisfy(caveat -> assertThat(caveat.toString()).contains("última instantánea"));

		var byChapter = assertThat(mvc.get().uri("/api/v1/spending/budget/aggregations").param("by", "chapter"))
				.hasStatusOk().bodyJson();
		byChapter.extractingPath("$.item.basis").isEqualTo("single-snapshot");
		byChapter.extractingPath("$.item.snapshotDate").isEqualTo("2026-08-31");
		byChapter.extractingPath("$.item.lines").isEqualTo(2);

		// El grupo sin programa sale como tal: esconderlo borraría el hecho de que la clasificación no existía.
		var byProgramme = assertThat(mvc.get().uri("/api/v1/spending/budget/aggregations")
				.param("by", "programme").param("date", "2006-12-31")).hasStatusOk().bodyJson();
		byProgramme.extractingPath("$.item.items[*].key").asArray().containsNull();

		assertThat(mvc.get().uri("/api/v1/spending/budget/aggregations").param("by", "junta"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- API: resumen, con los huecos delante -----------------------------------------------------------
		var summary = assertThat(mvc.get().uri("/api/v1/spending/budget/summary")).hasStatusOk().bodyJson();
		summary.extractingPath("$.item.snapshots").isEqualTo(4);
		summary.extractingPath("$.item.byReadStatus.LOADED").isEqualTo(3);
		summary.extractingPath("$.item.byReadStatus.ABSENT").isEqualTo(1);
		summary.extractingPath("$.item.lines").isEqualTo(6);
		summary.extractingPath("$.item.latestLoaded").isEqualTo("2026-08-31");
		summary.extractingPath("$.item.redactedHeadings").isEqualTo(1);
		summary.extractingPath("$.item.linesWithoutProgramme").isEqualTo(1);

		assertThat(budget.latestLoaded()).contains(LATEST);
	}

	// --- ayudas -------------------------------------------------------------------------------------------

	private void expectCensus() {
		String urls = ALL.stream().map(date -> "\"" + BASE + "/fecha/" + date.toString().replace("-", "") + "\"")
				.reduce((a, b) -> a + "," + b).orElse("");
		server.expect(requestTo(Matchers.startsWith(CENSUS_URL)))
				.andRespond(withSuccess("{\"totalCount\":" + ALL.size() + ",\"start\":0,\"rows\":" + ALL.size()
						+ ",\"result\":[" + urls + "]}", JSON_UTF8));
	}

	/**
	 * Las cuatro instantáneas, respondiendo según la fecha pedida para no depender del orden del lote. Cada
	 * petición tiene que llevar {@code sort=id asc}: es la defensa entera contra el orden por defecto (S3.2 §2).
	 */
	private void expectSnapshots() {
		server.expect(ExpectedCount.times(ALL.size()),
				requestTo(Matchers.allOf(Matchers.startsWith(BASE + "/fecha/"),
						Matchers.containsString("sort=id%20asc"))))
				.andRespond(request -> {
					String path = request.getURI().getPath();
					String date = path.substring(path.lastIndexOf('/') + 1).replace(".json", "");
					if (date.equals("20260731")) {
						return withStatus(HttpStatus.NOT_FOUND).body("{\"status\":400}").contentType(JSON_UTF8)
								.createResponse(request);
					}
					return withSuccess(bodyFor(date), JSON_UTF8).createResponse(request);
				});
	}

	private static String bodyFor(String date) {
		return switch (date) {
			case "20061231" -> """
					{"totalCount":2,"start":0,"rows":2,"result":[
					{"id":"20061231-06HAC--0111116000","idArea":"02","area":"HACIENDA",
					 "partida":"PENSION A LA VIUDA DE D. NOMBRE APELLIDO","idCapitulo":1,
					 "capitulo":"Gasto de Personal","idEpigrafe":"16000  ","epigrafe":"PENSIONES     ",
					 "idOrgano":"HAC","organo":"HACIENDA","creditoInicial":1200,"creditoModificacion":0,
					 "creditoDefinitivo":1200,"gastoComprometido":1200,"obligacionNeta":1200,"pagoNeto":1200,
					 "obligacionPendientePago":0,"remanenteDeCredito":0,"fecha":"20061231",
					 "concepto":"06HAC--0111116000"},
					{"id":"20061231-06ACS--3132122690","idArea":"04","area":"EDUCACION Y ACCION SOCIAL",
					 "partida":"PLAN ESTRATEGICO SERVICIOS SOCIALES","idCapitulo":2,
					 "capitulo":"Gastos en Bienes Corrientes y Servicios","idEpigrafe":"22690  ",
					 "epigrafe":"OTROS GASTOS DIVERSOS   ","idPrograma":"3132",
					 "programa":"PLANIFICACION SOCIAL","idOrgano":"ACS","organo":"ACCION SOCIAL",
					 "creditoInicial":50000,"creditoModificacion":0,"creditoDefinitivo":50000,
					 "gastoComprometido":37464.63,"obligacionNeta":37464.63,"pagoNeto":0,
					 "obligacionPendientePago":37464.63,"remanenteDeCredito":12535.37,"fecha":"20061231",
					 "concepto":"06ACS--3132122690"}]}
					""";
			case "20251231" -> """
					{"totalCount":2,"start":0,"rows":2,"result":[
					{"id":"20251231-25ALC--9201-22699","idArea":"01","area":"ALCALDIA",
					 "partida":"ALQUILER EDIFICIOS","idCapitulo":2,
					 "capitulo":"Gastos en Bienes Corrientes y Servicios","idEpigrafe":"22699",
					 "epigrafe":"OTROS GASTOS DIVERSOS.","idPrograma":"9201","programa":"ADMINISTRACION GENERAL",
					 "idOrgano":"ALC","organo":"ALCALDIA","creditoInicial":10000,"creditoModificacion":500,
					 "creditoDefinitivo":10500,"gastoComprometido":9000,"obligacionNeta":8000,"pagoNeto":7000,
					 "obligacionPendientePago":1000,"remanenteDeCredito":2500,"fecha":"20251231",
					 "concepto":"25ALC--9201-22699"},
					{"id":"20251231-25GUR--1513-6190325","idArea":"03","area":"URBANISMO",
					 "partida":"REGENERACION BARRIOS","idCapitulo":6,"capitulo":"Inversiones Reales",
					 "idEpigrafe":"6190325","epigrafe":"MANTENIMIENTO GENERAL","idPrograma":"1513",
					 "programa":"OTRAS ACTUACIONES DE URBANISMO","idOrgano":"GUR","organo":"GERENCIA DE URBANISMO",
					 "creditoInicial":200000,"creditoModificacion":0,"creditoDefinitivo":200000,
					 "gastoComprometido":150000,"obligacionNeta":120000,"pagoNeto":100000,
					 "obligacionPendientePago":20000,"remanenteDeCredito":80000,"fecha":"20251231",
					 "concepto":"25GUR--1513-6190325"}]}
					""";
			default -> """
					{"totalCount":2,"start":0,"rows":2,"result":[
					{"id":"20260831-26ACS--2311-21200","idArea":"08","area":"POLITICAS SOCIALES",
					 "partida":"MANTENIMIENTO EQUIPAMIENTOS","idCapitulo":2,
					 "capitulo":"Gastos en Bienes Corrientes y Servicios","idEpigrafe":"21200",
					 "epigrafe":"EDIFICIOS Y OTRAS CONSTRUCCIONES.","idPrograma":"2311",
					 "programa":"SERVICIOS GENERALES DE ACCION SOCIAL","idOrgano":"ACS","organo":"ACCION SOCIAL",
					 "creditoInicial":45000,"creditoModificacion":0,"creditoDefinitivo":45000,
					 "gastoComprometido":26097.77,"obligacionNeta":26097.77,"pagoNeto":24906.85,
					 "obligacionPendientePago":1190.92,"remanenteDeCredito":18902.23,"fecha":"20260831",
					 "concepto":"26ACS--2311-21200"},
					{"id":"20260831-26GUR--1513-6190325","idArea":"03","area":"URBANISMO",
					 "partida":"REGENERACION BARRIOS","idCapitulo":6,"capitulo":"Inversiones Reales",
					 "idEpigrafe":"6190325","epigrafe":"MANTENIMIENTO GENERAL","idPrograma":"1513",
					 "programa":"OTRAS ACTUACIONES DE URBANISMO","idOrgano":"GUR","organo":"GERENCIA DE URBANISMO",
					 "creditoInicial":0,"creditoModificacion":190304.68,"creditoDefinitivo":190304.68,
					 "gastoComprometido":5287.7,"obligacionNeta":2630.84,"pagoNeto":2630.84,
					 "obligacionPendientePago":0,"remanenteDeCredito":187673.84,"fecha":"20260831",
					 "concepto":"26GUR--1513-6190325"}]}
					""";
		};
	}

	private String status(LocalDate date) {
		return jdbc.sql("select read_status from spending_budget_snapshot where snapshot_date = ?").param(date)
				.query(String.class).single();
	}

	private Object nextAttempt(LocalDate date) {
		return jdbc.sql("select next_attempt_at from spending_budget_snapshot where snapshot_date = ?").param(date)
				.query(java.sql.Timestamp.class).optional().orElse(null);
	}

	private IngestionJob job(DatasetRef dataset) {
		return jobs.stream().filter(j -> dataset.equals(j.source().dataset())).findFirst().orElseThrow();
	}

}
