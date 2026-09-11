package es.zaragoza.observatory.spending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import es.zaragoza.observatory.TestcontainersConfiguration;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.shared.DatasetRef;

/**
 * Las subvenciones de punta a punta con PostgreSQL real y la API municipal simulada con documentos con la forma
 * de los que grabó S3.3. Lo que solo aquí se puede comprobar:
 * <ul>
 * <li>que los cuatro recursos se ingieren <b>sin orden</b> y el resultado no cambia: el enlace llega antes que
 * las concesiones y el directorio se relee después, y nadie recupera un nombre (ADR-018 §4);</li>
 * <li>que la identidad de una persona física <b>no llega a la base de datos</b>, y que la base de datos la
 * rechazaría aunque el traductor cambiara (regla 22);</li>
 * <li>que un título con un documento de identidad <b>no se puede insertar</b>, que es la restricción con
 * expresión regular de V014;</li>
 * <li>que el listado, la agregación y el resumen funcionan sobre PostgreSQL, que es donde vive el SQL.</li>
 * </ul>
 */
@SpringBootTest(properties = { "zaragoza.ingestion.scheduler.enabled=false", "zaragoza.ingestion.page-delay=PT0S",
		"zaragoza.ingestion.retry.initial-backoff=PT0.01S", "zaragoza.catalog.observation.enabled=false",
		"zaragoza.spending.releases.enabled=false", "zaragoza.spending.budget.enabled=false",
		"zaragoza.spending.grants.full-sweep=true" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockRestServiceServer
@AutoConfigureMockMvc
class GrantsIntegrationTests {

	static final String V1 = "https://www.zaragoza.es/sede/servicio/ayuda-subvencion";

	static final String V2 = "https://www.zaragoza.es/sede/servicio/ayuda-subvencion-v2";

	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

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
	void ingiereLosCuatroRecursosEnCualquierOrdenYSirveLaApiSinIdentidadDePersona() {
		// --- el enlace PRIMERO, antes de que exista ninguna concesión ni ningún beneficiario ------------------
		expectLinks();
		IngestionRunSummary links = ingestion.run(job(SpendingSources.GRANT_LINKS));

		assertThat(links.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(count("spending_grant_link")).isEqualTo(3);
		// Un enlace de una concesión que todavía no existe se guarda igual: no hay clave ajena a propósito.
		assertThat(count("spending_grant")).isZero();

		// --- el directorio DESPUÉS: no puede devolverle el nombre a quien el enlace marcó como persona --------
		server.reset();
		expectBeneficiaries();
		assertThat(ingestion.run(job(SpendingSources.GRANT_BENEFICIARIES)).status()).isEqualTo(RunStatus.SUCCEEDED);

		assertThat(count("spending_grant_beneficiary")).isEqualTo(3);
		// 4565 lo clasifica el directorio como `otros` y el enlace le puso el identificador enmascarado: gana la
		// unión de las dos señales, y con ella se va el nombre que el directorio traía (ADR-018 §4, S3.3 §7).
		assertThat(naturalPerson("4565")).isTrue();
		assertThat(name("4565")).isNull();
		// 626 es persona física por clasificación y nunca tuvo nombre.
		assertThat(naturalPerson("626")).isTrue();
		assertThat(name("626")).isNull();
		// 9999 no lo es por ninguna de las dos: conserva razón social y el NIF que trajo el enlace.
		assertThat(naturalPerson("9999")).isFalse();
		assertThat(name("9999")).isEqualTo("CLUB PATIN ZARAGOZA");
		assertThat(legalNif("9999")).isEqualTo("G50423219");
		// Y de nadie hay un dato de contacto: la tabla no tiene dónde ponerlo.
		assertThat(columns("spending_grant_beneficiary"))
				.doesNotContain("street_address", "postal_code", "contact_point_telephone", "contact_point_email");

		// --- las convocatorias y, por fin, las concesiones ---------------------------------------------------
		server.reset();
		expectCalls();
		assertThat(ingestion.run(job(SpendingSources.GRANT_CALLS)).status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(count("spending_grant_call")).isEqualTo(2);

		server.reset();
		expectGrants();
		IngestionRunSummary grants = ingestion.run(job(SpendingSources.GRANTS));

		assertThat(grants.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(count("spending_grant")).isEqualTo(4);
		// El título con el DNI dentro entró redactado, y ninguna fila lleva algo con forma de documento.
		assertThat(jdbc.sql("select count(*) from spending_grant where title_redacted").query(Long.class).single())
				.isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from spending_grant where title ~ '\\m[0-9]{8}[A-Za-z]\\M'")
				.query(Long.class).single()).isZero();

		// --- la API ------------------------------------------------------------------------------------------
		var list = mvc.get().uri("/api/v1/spending/grants?sort=granted,desc").assertThat().hasStatusOk()
				.bodyJson();
		list.extractingPath("$.unit").isEqualTo("concesiones");
		list.extractingPath("$.total").isEqualTo(4);
		list.extractingPath("$.items[0].granted").isEqualTo(45000.00);
		list.extractingPath("$.caveats").asList().isNotEmpty();

		// La concesión de una persona física sale contada y no nombrada.
		var person = mvc.get().uri("/api/v1/spending/grants/2808").assertThat().hasStatusOk().bodyJson();
		person.extractingPath("$.item.naturalPerson").isEqualTo(true);
		person.extractingPath("$.item.beneficiaryId").isEqualTo("626");
		person.extractingPath("$.item.beneficiary").isEqualTo(null);

		// Y la de una entidad, nombrada.
		var entity = mvc.get().uri("/api/v1/spending/grants/2851").assertThat().hasStatusOk().bodyJson();
		entity.extractingPath("$.item.naturalPerson").isEqualTo(false);
		entity.extractingPath("$.item.beneficiary").isEqualTo("CLUB PATIN ZARAGOZA");
		entity.extractingPath("$.item.call").isEqualTo("SUBVENCION DEPORTE BASE");

		// La concesión sin enlace —las de 2013 y 2014 en la fuente real— sale sin beneficiario, no se inventa.
		var orphan = mvc.get().uri("/api/v1/spending/grants/1").assertThat().hasStatusOk().bodyJson();
		orphan.extractingPath("$.item.beneficiaryId").isEqualTo(null);
		orphan.extractingPath("$.item.naturalPerson").isEqualTo(null);

		// El filtro que deja separar sin que nada cambie de valor por defecto (la figura de ADR-015).
		mvc.get().uri("/api/v1/spending/grants?naturalPerson=true").assertThat().hasStatusOk().bodyJson()
				.extractingPath("$.total").isEqualTo(1);
		mvc.get().uri("/api/v1/spending/grants?naturalPerson=false").assertThat().hasStatusOk().bodyJson()
				.extractingPath("$.total").isEqualTo(1);

		// --- agregaciones ------------------------------------------------------------------------------------
		var byYear = mvc.get().uri("/api/v1/spending/grants/aggregations?by=year").assertThat().hasStatusOk()
				.bodyJson();
		byYear.extractingPath("$.item.by").isEqualTo("year");
		byYear.extractingPath("$.item.unit").isEqualTo("concesiones");
		byYear.extractingPath("$.item.buckets").asList().isNotEmpty();

		// En el eje de beneficiario, la persona física se cuenta y no se nombra.
		var byBeneficiary = mvc.get().uri("/api/v1/spending/grants/aggregations?by=beneficiary").assertThat()
				.hasStatusOk().bodyJson();
		byBeneficiary.extractingPath("$.item.buckets[?(@.key=='626')].label").asList().containsExactly((Object) null);
		byBeneficiary.extractingPath("$.item.buckets[?(@.key=='9999')].label").asList()
				.containsExactly("CLUB PATIN ZARAGOZA");

		var byClassification = mvc.get().uri("/api/v1/spending/grants/aggregations?by=classification").assertThat()
				.hasStatusOk().bodyJson();
		byClassification.extractingPath("$.item.buckets").asList().isNotEmpty();

		// --- convocatorias y resumen -------------------------------------------------------------------------
		mvc.get().uri("/api/v1/spending/grants/calls").assertThat().hasStatusOk().bodyJson()
				.extractingPath("$.total").isEqualTo(2);

		var summary = mvc.get().uri("/api/v1/spending/grants/summary").assertThat().hasStatusOk().bodyJson();
		summary.extractingPath("$.item.grants").isEqualTo(4);
		summary.extractingPath("$.item.calls").isEqualTo(2);
		summary.extractingPath("$.item.beneficiaries").isEqualTo(3);
		summary.extractingPath("$.item.naturalPersonBeneficiaries").isEqualTo(2);
		summary.extractingPath("$.item.naturalPersonGrants").isEqualTo(1);
		// Las dos concesiones sin enlace: en la fuente real son las 2.609 de 2013 y 2014 que la v2 no publica.
		summary.extractingPath("$.item.withoutBeneficiary").isEqualTo(2);
		summary.extractingPath("$.item.redactedTitles").isEqualTo(1);
		summary.extractingPath("$.item.impossibleDates").isEqualTo(1);

		mvc.get().uri("/api/v1/spending/grants?sort=inventado,desc").assertThat().hasStatus(400);
		mvc.get().uri("/api/v1/spending/grants/aggregations?by=barrio").assertThat().hasStatus(400);
	}

	/**
	 * La otra mitad de la garantía, la que no depende del traductor: aunque alguien lo cambiara, la base de
	 * datos no deja guardar ni identidad de persona física ni un título con un documento dentro (V014).
	 */
	@Test
	void laBaseDeDatosRechazaLaIdentidadDeUnaPersonaAunqueElTraductorCambiara() {
		assertThatThrownBy(() -> jdbc.sql("""
				insert into spending_grant_beneficiary (id, name, legal_nif, natural_person, masked_identifier,
				    classification, first_seen_at, last_seen_at)
				values ('t1', 'UN NOMBRE', null, true, false, 'personas-fisicas', now(), now())
				""").update()).hasMessageContaining("spending_grant_beneficiary_no_natural_identity");

		assertThatThrownBy(() -> jdbc.sql("""
				insert into spending_grant (id, title, title_redacted, first_seen_at, last_seen_at)
				values (999999, 'AYUDA A 12345678Z', false, now(), now())
				""").update()).hasMessageContaining("spending_grant_title_has_no_identity");

		assertThatThrownBy(() -> jdbc.sql("""
				insert into spending_grant (id, title, title_redacted, first_seen_at, last_seen_at)
				values (999998, 'AYUDA A X1234567L', false, now(), now())
				""").update()).hasMessageContaining("spending_grant_title_has_no_identity");
	}

	// --- respuestas simuladas ------------------------------------------------------------------------------

	/** El censo: cuatro concesiones, una sin enlace, una con el DNI en el título y una con fecha imposible. */
	private void expectGrants() {
		server.expect(requestTo(Matchers.allOf(Matchers.startsWith(V1 + "/resolucion.json"),
				Matchers.containsString("sort=id%20asc"),
				Matchers.not(Matchers.containsString("adjudicatario")))))
				.andRespond(withSuccess("""
						{"totalCount":4,"start":0,"rows":500,"result":[
						 {"id":1,"title":"APOYO ASOCIACION PENSIONISTAS","expediente":"0618143/2014",
						  "importeSolicitado":0,"importeConcedido":45000,"importeAnual":45000,
						  "numAnualidades":2014,"fechaSolicitud":"2014-11-19T00:00:00",
						  "fechaConcesion":"2014-10-17T00:00:00","convocatoria":{"id":55}},
						 {"id":9,"title":"AYUDA CON FECHA IMPOSIBLE","importeConcedido":300,
						  "fechaConcesion":"0019-11-28T00:00:00","convocatoria":{"id":55}},
						 {"id":2808,"title":"LINEA 2.2: OBRAS EN VIVIENDAS, NIF 12345678Z",
						  "expediente":"0005771/2025","importeSolicitado":5237,"importeConcedido":1225,
						  "fechaConcesion":"2015-11-20T00:00:00","convocatoria":{"id":206}},
						 {"id":2851,"title":"VII TROFEO PATINAJE","importeConcedido":200,
						  "fechaConcesion":"2015-03-20T00:00:00","convocatoria":{"id":206}}]}
						""", JSON_UTF8));
	}

	private void expectCalls() {
		server.expect(requestTo(Matchers.allOf(Matchers.startsWith(V1 + "/convocatoria.json"),
				Matchers.containsString("sort=id%20asc"),
				Matchers.not(Matchers.containsString("resolucion")))))
				.andRespond(withSuccess("""
						{"totalCount":2,"start":0,"rows":500,"result":[
						 {"id":55,"title":"SUBVENCION ASOCIACIONES DE VECINOS","ejercicioClave":"2014",
						  "esPlurianual":false,"presupuesto":4017,"porcentajeAnticipado":80,
						  "gestor":{"id":29,"title":"Concejal Presidente de la Junta Municipal de Torrero"},
						  "tipo":{"id":1,"title":"Concurrencia Competitiva"},
						  "lineaEstrategica":{"id":8,"lineaAuxiliar":{"id":8,"title":"3 - Asociacionismo"}}},
						 {"id":206,"title":"SUBVENCION DEPORTE BASE","ejercicioClave":"2015",
						  "esPlurianual":false,"presupuesto":120000,
						  "gestor":{"id":33,"title":"Concejalia Delegada de Deportes"},
						  "tipo":{"id":2,"title":"Directa"},
						  "lineaEstrategica":{"id":9,"lineaAuxiliar":{"id":9,"title":"4 - Deporte"}}}]}
						""", JSON_UTF8));
	}

	private void expectBeneficiaries() {
		server.expect(requestTo(Matchers.allOf(Matchers.startsWith(V2 + "/organization.json"),
				Matchers.containsString("pageSize="), Matchers.not(Matchers.containsString("&page=")))))
				.andRespond(withSuccess("""
						{"page":1,"pageSize":500,"totalRecords":3,"pageRecords":3,"records":[
						 {"id":"626","title":"Datos de caracter personal","municipioTitle":"Zaragoza",
						  "classification":"<http://vocab.linkeddata.es/datosabiertos/kos/sector-publico/convenio/tipo-entidad/personas-fisicas>"},
						 {"id":"4565","title":"UNA RAZON SOCIAL","streetAddress":"CALLE MAYOR 1",
						  "postalCode":"50001","contactPointTelephone":"976000000",
						  "contactPointEmail":"alguien@example.org",
						  "classification":"<http://vocab.linkeddata.es/datosabiertos/kos/sector-publico/convenio/tipo-entidad/otros>"},
						 {"id":"9999","title":"CLUB PATIN ZARAGOZA","streetAddress":"CALLE MENOR 2",
						  "classification":"<http://vocab.linkeddata.es/datosabiertos/kos/sector-publico/convenio/tipo-entidad/entidad-deportiva>"}]}
						""", JSON_UTF8));
	}

	private void expectLinks() {
		server.expect(requestTo(Matchers.allOf(Matchers.startsWith(V2 + "/concesion.json"),
				Matchers.containsString("sort=id%20asc"))))
				.andRespond(withSuccess("""
						{"page":1,"pageSize":500,"totalRecords":3,"pageRecords":3,"records":[
						 {"id":2808,"title":"LINEA 2.2","importeConcedido":1225,"beneficiario":"626",
						  "nifcif":"***332**"},
						 {"id":2850,"title":"OTRA","importeConcedido":100,"beneficiario":"4565",
						  "nifcif":"***720**"},
						 {"id":2851,"title":"VII TROFEO","importeConcedido":200,"beneficiario":"9999",
						  "nifcif":"G50423219"}]}
						""", JSON_UTF8));
	}

	// --- ayudas --------------------------------------------------------------------------------------------

	private long count(String table) {
		return jdbc.sql("select count(*) from " + table).query(Long.class).single();
	}

	private Boolean naturalPerson(String id) {
		return jdbc.sql("select natural_person from spending_grant_beneficiary where id = ?").param(id)
				.query(Boolean.class).single();
	}

	private String name(String id) {
		return jdbc.sql("select name from spending_grant_beneficiary where id = ?").param(id).query(String.class)
				.optional().orElse(null);
	}

	private String legalNif(String id) {
		return jdbc.sql("select legal_nif from spending_grant_beneficiary where id = ?").param(id)
				.query(String.class).optional().orElse(null);
	}

	private java.util.List<String> columns(String table) {
		return jdbc.sql("select column_name from information_schema.columns where table_name = ?").param(table)
				.query(String.class).list();
	}

	private IngestionJob job(DatasetRef dataset) {
		return jobs.stream().filter(j -> dataset.equals(j.source().dataset())).findFirst().orElseThrow();
	}

}
