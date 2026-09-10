package es.zaragoza.observatory.spending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
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
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.spending.application.ReadReleases;
import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;
import es.zaragoza.observatory.spending.domain.ProcessQuery;
import es.zaragoza.observatory.spending.domain.ReleaseStatus;
import es.zaragoza.observatory.support.Fixtures;

/**
 * El módulo {@code spending} de punta a punta con PostgreSQL real y la API municipal simulada con los documentos
 * que grabó S3.1: censo con el interruptor puesto, marcado del listado documentado, lectura del detalle por
 * lotes con sus cuatro desenlaces y la API de lectura.
 * <p>
 * Tres cosas se comprueban aquí porque solo aquí se pueden comprobar:
 * <ul>
 * <li>que el identificador crudo de las partes —el que lleva el NIF dentro— <b>no tiene dónde guardarse</b>, y
 * que la base de datos rechaza una persona física con identidad (las dos mitades de ADR-017 §2);</li>
 * <li>que un proceso sin release <b>sigue existiendo</b> con su ocid y sale en el resumen (ADR-017 §5);</li>
 * <li>que las agregaciones y sus filtros funcionan sobre PostgreSQL, que es donde vive el SQL del adaptador.</li>
 * </ul>
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S", "zaragoza.catalog.observation.enabled=false",
		"zaragoza.spending.releases.enabled=false", "zaragoza.spending.releases.request-delay=PT0S" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@AutoConfigureMockMvc
class SpendingIntegrationTests {

	static final String LIST_URL = "https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds/"
			+ "contracting-process.json";

	static final String DETAIL_PREFIX = "https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds/"
			+ "contracting-process/";

	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	/** Con licitación viva: uno de los 305 que sí sostienen la etapa `planned`. */
	static final String TENDER = "ocds-1xraxc-0-ContractingProcess";

	/** Proceso completo con un contrato que es una cáscara: uno de los 1.560 que se quedan sin etapa. */
	static final String SHELL = "ocds-1xraxc-8-ContractingProcess";

	/** Con adjudicación, adjudicataria con NIF y contrato firmado. */
	static final String AWARDED = "ocds-1xraxc-6621-ContractingProcess";

	/** 404: su release aún no está publicado. Es el 29,7 % del universo. */
	static final String ABSENT = "ocds-1xraxc-9000-ContractingProcess";

	/** 200 con `releases` vacío: uno de los 16 que demuestran que un 200 no garantiza release. */
	static final String EMPTY = "ocds-1xraxc-9001-ContractingProcess";

	static final List<String> ALL = List.of(TENDER, SHELL, AWARDED, ABSENT, EMPTY);

	/** El listado documentado esconde dos de los cinco, como el real esconde 2.271 de 8.001. */
	static final List<String> DOCUMENTED = List.of(TENDER, SHELL, AWARDED);

	@Autowired
	Ingestion ingestion;

	@Autowired
	ObjectProvider<IngestionJob> jobs;

	@Autowired
	ContractingProcessRepository processes;

	@Autowired
	ReadReleases readReleases;

	@Autowired
	MockRestServiceServer server;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void censaLosProcesosLeeSuDetalleYSirveLaApi() {
		// --- censo: una sola petición, con el interruptor ---------------------------------------------------
		expectCensus(listOf(ALL));
		expectDocumentedList(listOf(DOCUMENTED));
		IngestionRunSummary run = ingestion.run(job(SpendingSources.PROCESSES));

		assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(run.records()).isEqualTo(5);
		assertThat(processes.count()).isEqualTo(5);

		// El censo va con `after`, que no es una fecha sino el interruptor de ADR-017 §1, y sin `sort`, porque la
		// fuente lo acepta y no lo aplica.
		var query = job(SpendingSources.PROCESSES).source().query();
		assertThat(query).containsEntry("after", "2030-01-01T00:00:00Z").doesNotContainKey("sort");

		// ADR-017 §4: la página cruda del listado SÍ se guarda; son ocids y nada más.
		assertThat(jdbc.sql("""
				select count(*) from raw_payload where source = 'ocds' and dataset_id = 'contracting-process'
				""").query(Long.class).single()).isPositive();

		// El listado documentado lo pide el listener, que es asíncrono: se espera al registro de eventos de
		// Modulith, no a contar filas (la lección de UrbanIntegrationTests).
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc
				.sql("select count(*) from event_publication where completion_date is null").query(Long.class)
				.single()).isZero());
		assertThat(jdbc.sql("select count(*) from spending_process where in_documented_list").query(Long.class)
				.single()).isEqualTo(3);
		server.reset();

		// --- detalle: los cuatro desenlaces -----------------------------------------------------------------
		expectDetails();
		var batch = readReleases.readDue(10);

		assertThat(batch.requested()).isEqualTo(5);
		assertThat(batch.published()).isEqualTo(3);
		assertThat(batch.absent()).isEqualTo(1);
		assertThat(batch.empty()).isEqualTo(1);
		assertThat(batch.unreadable()).isZero();

		assertThat(status(TENDER)).isEqualTo("PUBLISHED");
		assertThat(status(ABSENT)).as("un 404 no borra el proceso: el ocid existe").isEqualTo("ABSENT");
		assertThat(status(EMPTY)).as("un 200 no garantiza release").isEqualTo("EMPTY");

		// ADR-017 §6: la etapa solo donde el documento la sostiene.
		assertThat(stage(TENDER)).isEqualTo("PLANNED");
		assertThat(stage(AWARDED)).isEqualTo("COMMITTED");
		assertThat(stage(SHELL)).as("contrato sin fecha de firma: sin etapa").isNull();
		assertThat(stage(ABSENT)).isNull();

		// --- ADR-017 §2: el identificador con el NIF dentro no tiene dónde guardarse ------------------------
		assertThat(columnsOf("spending_award_party")).containsExactlyInAnyOrder("ocid", "award_id", "ordinal",
				"tax_id", "name", "natural_person");
		assertThat(jdbc.sql("select tax_id, name from spending_award_party where ocid = ?").param(AWARDED)
				.query((rs, row) -> rs.getString("tax_id") + "|" + rs.getString("name")).single())
				.isEqualTo("B50892819|MARIANO-ESTAGE-SL");
		// Y la base de datos rechaza una persona física con identidad, aunque alguien cambiara el traductor.
		assertThatThrownBy(() -> jdbc.sql("""
				insert into spending_award_party (ocid, award_id, ordinal, tax_id, name, natural_person)
				values (?, '65236-award', 99, '12345678Z', 'Un nombre', true)
				""").param(AWARDED).update())
				.as("la mitad de la garantía que vive en la base de datos (ADR-017 §2)")
				.hasMessageContaining("spending_award_party_no_natural_identity");

		// --- el modelo: cáscaras, importes y CPV ------------------------------------------------------------
		assertThat(jdbc.sql("""
				select count(*) from spending_contract
				where award_id is null and signed_on is null and status is null and description is null
				""").query(Long.class).single()).as("la cáscara vacía se guarda tal cual").isEqualTo(1);
		assertThat(jdbc.sql("select awarded_amount from spending_process where ocid = ?").param(AWARDED)
				.query(java.math.BigDecimal.class).single()).isEqualByComparingTo("106471.8");
		assertThat(jdbc.sql("select count(*) from spending_process_cpv where ocid = ?").param(AWARDED)
				.query(Long.class).single()).isEqualTo(2);

		// --- segunda lectura: idempotente, sin acumular hijos ------------------------------------------------
		long awardsBefore = jdbc.sql("select count(*) from spending_award").query(Long.class).single();
		jdbc.sql("update spending_process set next_attempt_at = now() - interval '1 day'").update();
		server.reset();
		expectDetails();
		readReleases.readDue(10);

		assertThat(processes.count()).isEqualTo(5);
		assertThat(jdbc.sql("select count(*) from spending_award").query(Long.class).single())
				.as("las adjudicaciones se reemplazan, no se acumulan").isEqualTo(awardsBefore);
		assertThat(jdbc.sql("select attempts from spending_process where ocid = ?").param(ABSENT)
				.query(Integer.class).single()).as("y el reintento cuenta").isEqualTo(2);

		// --- API: listado y detalle -------------------------------------------------------------------------
		var listing = assertThat(mvc.get().uri("/api/v1/spending/processes").param("size", "5"))
				.hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON).bodyJson();
		listing.extractingPath("$.total").isEqualTo(5);
		listing.extractingPath("$.source.dataset").isEqualTo("ocds:contracting-process");
		listing.extractingPath("$.caveats").asArray().isNotEmpty();
		// La ausencia de territorio se dice, no se deja notar (ADR-003 §4).
		listing.extractingPath("$.caveats").asArray()
				.anySatisfy(caveat -> assertThat(caveat.toString()).contains("junta"));

		var detail = assertThat(mvc.get().uri("/api/v1/spending/processes/" + AWARDED)).hasStatusOk().bodyJson();
		detail.extractingPath("$.item.awards[0].suppliers[0].taxId").isEqualTo("B50892819");
		detail.extractingPath("$.item.awards[0].suppliers[0].naturalPerson").isEqualTo(false);
		detail.extractingPath("$.item.stage").isEqualTo("COMMITTED");
		detail.extractingPath("$.item.contracts[0].emptyShell").isEqualTo(false);
		detail.extractingPath("$.item.cpv").asArray().hasSize(2);
		// El DTO no publica el identificador crudo de la parte por ningún nombre.
		detail.extractingPath("$.item.awards[0].suppliers[0]").asMap().doesNotContainKeys("id", "sourceId");

		assertThat(mvc.get().uri("/api/v1/spending/processes/no-existe")).hasStatus(HttpStatus.NOT_FOUND);

		var shell = assertThat(mvc.get().uri("/api/v1/spending/processes/" + SHELL)).hasStatusOk().bodyJson();
		shell.extractingPath("$.item.stage").isNull();
		shell.extractingPath("$.item.contracts[0].emptyShell").isEqualTo(true);

		var absent = assertThat(mvc.get().uri("/api/v1/spending/processes/" + ABSENT)).hasStatusOk().bodyJson();
		absent.extractingPath("$.item.releaseStatus").isEqualTo("ABSENT");
		absent.extractingPath("$.item.title").isNull();
		absent.extractingPath("$.item.inDocumentedList").isEqualTo(false);

		// --- API: filtros -----------------------------------------------------------------------------------
		assertThat(mvc.get().uri("/api/v1/spending/processes").param("releaseStatus", "absent")).hasStatusOk()
				.bodyJson().extractingPath("$.total").isEqualTo(1);
		assertThat(mvc.get().uri("/api/v1/spending/processes").param("inDocumentedList", "false")).hasStatusOk()
				.bodyJson().extractingPath("$.total").isEqualTo(2);
		assertThat(mvc.get().uri("/api/v1/spending/processes").param("taxId", "B50892819")).hasStatusOk()
				.bodyJson().extractingPath("$.total").isEqualTo(1);
		assertThat(mvc.get().uri("/api/v1/spending/processes").param("cpv", "45232150")).hasStatusOk().bodyJson()
				.extractingPath("$.total").isEqualTo(1);
		assertThat(mvc.get().uri("/api/v1/spending/processes").param("q", "climatización")).hasStatusOk()
				.bodyJson().extractingPath("$.total").isEqualTo(1);
		assertThat(mvc.get().uri("/api/v1/spending/processes").param("sort", "inventado,asc"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/spending/processes").param("stage", "inventada"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/spending/processes").param("size", "5000"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		// Y no hay parámetro territorial que valga: esta fuente no publica localización.
		assertThat(mvc.get().uri("/api/v1/spending/aggregations").param("by", "district"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- API: agregaciones, con la unidad y el solapamiento declarados -----------------------------------
		var byYear = assertThat(mvc.get().uri("/api/v1/spending/aggregations").param("by", "year")).hasStatusOk()
				.bodyJson();
		byYear.extractingPath("$.item.unit").isEqualTo("processes");
		byYear.extractingPath("$.item.overlapping").isEqualTo(false);
		byYear.extractingPath("$.item.withoutRelease").isEqualTo(2);
		byYear.extractingPath("$.item.buckets").asArray().isNotEmpty();

		// El eje que enseña el hueco: sin él, el 29,7 % sin release solo se vería restando.
		var byRelease = assertThat(mvc.get().uri("/api/v1/spending/aggregations").param("by", "release_status"))
				.hasStatusOk().bodyJson();
		byRelease.extractingPath("$.item.buckets[?(@.key == 'ABSENT')].total").asArray().containsExactly(1);
		byRelease.extractingPath("$.item.matched").isEqualTo(5);

		// El grupo sin etapa sale como tal: es el hallazgo, no un residuo.
		var byStage = assertThat(mvc.get().uri("/api/v1/spending/aggregations").param("by", "stage")).hasStatusOk()
				.bodyJson();
		byStage.extractingPath("$.item.buckets[?(@.key == 'COMMITTED')].total").asArray().containsExactly(1);
		byStage.extractingPath("$.item.buckets").asArray().anySatisfy(bucket -> {
			assertThat(((java.util.Map<?, ?>) bucket).get("key")).as("el grupo sin etapa existe").isNull();
			assertThat(((java.util.Map<?, ?>) bucket).get("withoutStage")).isEqualTo(1);
		});

		var bySupplier = assertThat(mvc.get().uri("/api/v1/spending/aggregations").param("by", "supplier"))
				.hasStatusOk().bodyJson();
		bySupplier.extractingPath("$.item.unit").isEqualTo("awards");
		bySupplier.extractingPath("$.item.overlapping").isEqualTo(true);
		bySupplier.extractingPath("$.item.buckets[?(@.key == 'B50892819')].awardedAmount").asArray()
				.containsExactly(106471.8);
		// En este eje no hay importe licitado: contarlo por adjudicación lo duplicaría (ADR-017 §7).
		bySupplier.extractingPath("$.item.buckets[0].tenderedAmount").isNull();

		var byCpv = assertThat(mvc.get().uri("/api/v1/spending/aggregations").param("by", "cpv")).hasStatusOk()
				.bodyJson();
		byCpv.extractingPath("$.item.overlapping").isEqualTo(true);
		byCpv.extractingPath("$.item.buckets").asArray().hasSize(2);
		byCpv.extractingPath("$.caveats").asArray()
				.anySatisfy(caveat -> assertThat(caveat.toString()).contains("suma de los grupos"));

		assertThat(mvc.get().uri("/api/v1/spending/aggregations").param("by", "category")).hasStatusOk().bodyJson()
				.extractingPath("$.item.unit").isEqualTo("processes");
		assertThat(mvc.get().uri("/api/v1/spending/aggregations").param("by", "inventado"))
				.hasStatus(HttpStatus.BAD_REQUEST);

		// --- API: resumen, con los huecos delante -----------------------------------------------------------
		var summary = assertThat(mvc.get().uri("/api/v1/spending/summary")).hasStatusOk().bodyJson();
		summary.extractingPath("$.item.processes").isEqualTo(5);
		summary.extractingPath("$.item.byReleaseStatus.PUBLISHED").isEqualTo(3);
		summary.extractingPath("$.item.byReleaseStatus.ABSENT").isEqualTo(1);
		summary.extractingPath("$.item.byReleaseStatus.EMPTY").isEqualTo(1);
		summary.extractingPath("$.item.byReleaseStatus.PENDING").isEqualTo(0);
		summary.extractingPath("$.item.notInDocumentedList").isEqualTo(2);
		summary.extractingPath("$.item.withoutStage").isEqualTo(1);
		summary.extractingPath("$.item.emptyContracts").isEqualTo(1);
		summary.extractingPath("$.item.naturalPersonSuppliers").isEqualTo(0);
		// Los dos importes van por separado y ninguno se llama «importe» a secas.
		summary.extractingPath("$.item.tenderedAmount").isNotNull();
		summary.extractingPath("$.item.awardedAmount").isNotNull();
		summary.extractingPath("$.item").asMap().doesNotContainKeys("amount", "spent", "executedAmount");

		assertThat(processes.count(ProcessQuery.all())).isEqualTo(5);
		assertThat(processes.totals(ProcessQuery.all()).byReleaseStatus())
				.containsEntry(ReleaseStatus.PUBLISHED, 3L);
	}

	// --- ayudas -------------------------------------------------------------------------------------------

	private void expectCensus(String body) {
		server.expect(requestTo(Matchers.containsString("after=2030-01-01")))
				.andRespond(withSuccess(body, JSON_UTF8));
	}

	private void expectDocumentedList(String body) {
		server.expect(requestTo(
				Matchers.allOf(Matchers.startsWith(LIST_URL), Matchers.not(Matchers.containsString("after=")))))
				.andRespond(withSuccess(body, JSON_UTF8));
	}

	/** Los cinco detalles, respondiendo según el ocid pedido para no depender del orden del lote. */
	private void expectDetails() {
		server.expect(ExpectedCount.times(ALL.size()), requestTo(Matchers.startsWith(DETAIL_PREFIX)))
				.andRespond(request -> {
					String path = request.getURI().getPath();
					String ocid = path.substring(path.lastIndexOf('/') + 1).replace(".json", "");
					if (ABSENT.equals(ocid)) {
						// El 404 real llega con un cuerpo que dice 400: manda la cabecera (S3.1 §3).
						return withStatus(HttpStatus.NOT_FOUND)
								.body("{\"status\":400,\"mensaje\":\"" + ocid + " No valido: null\"}")
								.contentType(JSON_UTF8).createResponse(request);
					}
					String body = switch (ocid) {
						case TENDER -> Fixtures.text("ocds/contracting-process-tender.json");
						case SHELL -> Fixtures.text("ocds/contracting-process-contract.json");
						case AWARDED -> Fixtures
								.text("ocds/contracting-process-ocds-1xraxc-6621-ContractingProcess.json");
						default -> "{\"publishedDate\":\"2026-01-01T00:00:00Z\",\"releases\":[]}";
					};
					return withSuccess(body, JSON_UTF8).createResponse(request);
				});
	}

	private static String listOf(List<String> ocids) {
		return ocids.stream().map(ocid -> "{\"ocid\":\"" + ocid + "\",\"id\":\"" + ocid + "\"}")
				.reduce((a, b) -> a + "," + b).map(items -> "[" + items + "]").orElse("[]");
	}

	private String status(String ocid) {
		return jdbc.sql("select release_status from spending_process where ocid = ?").param(ocid)
				.query(String.class).single();
	}

	private String stage(String ocid) {
		return jdbc.sql("select stage from spending_process where ocid = ?").param(ocid).query(String.class)
				.optional().orElse(null);
	}

	private IngestionJob job(DatasetRef dataset) {
		return jobs.stream().filter(j -> dataset.equals(j.source().dataset())).findFirst().orElseThrow();
	}

	private List<String> columnsOf(String table) {
		return jdbc.sql("select column_name from information_schema.columns where table_name = ?").param(table)
				.query(String.class).list();
	}

}
