package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import es.zaragoza.observatory.spikes.support.SpikeFixtures;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S3.3 — Subvenciones ({@code ayuda-subvencion} y {@code ayuda-subvencion-v2}): la tercera y última fuente del
 * contexto {@code spending}. Informe: docs/spikes/S3.3-subvenciones-ingesta.md; decisión: ADR-018.
 * <p>
 * Las cuatro fuentes anteriores dejaron cuatro respuestas distintas a la regla 22, y ninguna sirve aquí:
 * <ul>
 * <li><b>ADR-012</b> (quejas): «no lo pidas». Vale si el dato personal está en un campo que se puede no pedir.</li>
 * <li><b>ADR-016</b> (locales): «no le des sitio». Vale si no se puede evitar descargarlo.</li>
 * <li><b>ADR-017</b> (contratación): «descompón el identificador». Vale si el NIF viene incrustado en un campo
 * estructural.</li>
 * <li><b>S3.2</b> (presupuesto): «lista cerrada». Vale si son cuatro filas contadas a mano.</li>
 * </ul>
 * Aquí el beneficiario <b>es</b> el dato: sin él, una subvención es un importe sin destinatario. Así que lo
 * primero que hay que medir es qué publica de verdad la fuente y en qué proporción, porque de eso depende todo
 * lo demás. Y lo que se encontró no estaba previsto: la fuente <b>se contradice</b>. Enmascara el NIF, titula
 * «Datos de caracter personal» a las personas físicas de su directorio… y publica el nombre completo en
 * {@code adjudicatario.nombre} y el DNI dentro del {@code title}.
 * <p>
 * Ningún valor personal se imprime ni se guarda: este spike cuenta patrones y formas.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S33GrantsSpike {

	static final String ID = "S3.3-subvenciones-ingesta";
	static final String FIXTURES = "grants";

	static final String V1 = SEDE + "/ayuda-subvencion";
	static final String V2 = SEDE + "/ayuda-subvencion-v2";

	/** Tope de página de la sede para {@code rows} (S0.5, regla 18). */
	static final int ROWS = 500;

	/**
	 * Proyección de la ingesta de concesiones. <b>Es la garantía de ADR-018</b>: {@code adjudicatario} no está,
	 * así que el nombre de la persona física no se descarga (misma figura que ADR-012).
	 * <p>
	 * De {@code convocatoria} solo hace falta el identificador, pero nombrarla trae su objeto entero: la
	 * proyección recorta por <b>subárbol</b>, no por campo, y {@code convocatoria.id} devuelve lo mismo que
	 * {@code convocatoria} (§3). Son 32 MB en vez de 12 en el barrido inicial, y ninguno después: la ingesta
	 * diaria va por marca de agua.
	 */
	static final String CONCESSION_FIELDS = "id,title,expediente,importeSolicitado,importeConcedido,importeAnual,"
			+ "numAnualidades,fechaSolicitud,fechaConcesion,fechaAcuerdo,convocatoria";

	/**
	 * Proyección de la ingesta de convocatorias. Excluye {@code resolucion[]}, que es una segunda copia del
	 * conjunto entero de concesiones <b>con los nombres dentro</b>, y usa rutas con punto para que el tercer
	 * nivel no llegue vacío (§3).
	 */
	static final String CALL_FIELDS = "id,title,ejercicioClave,esPlurianual,fechaInicioVigencia,fechaFinVigencia,"
			+ "fechaInicioPresentacion,fechaFinPresentacion,presupuesto,porcentajeAnticipado,gestor,funciones,"
			+ "objetos,tipo,lineaEstrategica.lineaAuxiliar,lineaAmbito.ambito";

	/** Pausa entre peticiones del barrido: la fuente es pública y no se le hace un barrido a pelo. */
	static final Duration PAUSE = Duration.ofMillis(40);

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(180));

	// --- patrones de identidad (se cuentan, nunca se imprimen) ----------------------------------------------

	static final String CHECK_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

	static final Pattern DNI = Pattern.compile("\\b(\\d{8})([A-Za-z])\\b");

	static final Pattern NIE = Pattern.compile("\\b([XYZxyz])(\\d{7})([A-Za-z])\\b");

	/** NIF de persona jurídica: letra inicial de forma societaria. Un DNI nunca casa aquí. */
	static final Pattern LEGAL_NIF = Pattern.compile("^[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]$");

	/** El enmascarado con el que la fuente publica el DNI de una persona física: {@code ***332**}. */
	static final Pattern MASKED = Pattern.compile("^[*\\d]*\\*[*\\d]*$");

	/** Texto constante con el que la fuente sustituye el NIF que no publica. */
	static final String NIF_PLACEHOLDER = "NIF";

	/** Texto constante con el que la fuente sustituye la razón social que no publica. */
	static final String NAME_PLACEHOLDER = "RAZÓN SOCIAL";

	/** Texto constante con el que la fuente sustituye el nombre de una persona física que no publica. */
	static final String PERSON_PLACEHOLDER = "NOMBRE APELLIDO1 APELLIDO2";

	/** Título con el que la fuente nombra a una persona física en su directorio de entidades. */
	static final String PERSONAL_DATA = "Datos de caracter personal";

	// --- estado compartido entre pruebas -------------------------------------------------------------------

	/** Identificadores de concesión vistos en el barrido de la v1 (el censo completo). */
	static final Set<Integer> v1Ids = new TreeSet<>();

	/** Identificadores de concesión vistos en el barrido de la v2. */
	static final Set<Integer> v2Ids = new TreeSet<>();

	/** Para cada concesión de la v2: el id de beneficiario y la clase de su {@code nifcif}. */
	static final Map<Integer, String> v2Beneficiary = new TreeMap<>();

	static final Map<Integer, String> v2NifKind = new TreeMap<>();

	/** Para cada beneficiario del directorio: su clasificación. */
	static final Map<String, String> beneficiaryClass = new TreeMap<>();

	/** Recuento de identidades encontradas por campo y tipo (regla 22). */
	static final Map<String, Integer> identity = new TreeMap<>();

	static final Map<String, Long> yearAmount = new TreeMap<>();

	static final Map<String, Integer> yearCount = new TreeMap<>();

	static int sweepRequests;

	static long sweepBytes;

	static long sweepMillis;

	@BeforeAll
	static void startMetrics() {
		SpikeFixtures.startMetrics(ID);
	}

	// -------------------------------------------------------------------------------------------------------
	// §1 — Qué publica el tag: quince paths documentados y dos familias con envoltorios distintos
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(1)
	void documentedResources() {
		heading(ID, "§1 Los recursos del tag «Ayuntamiento: Ayudas y Subvenciones»");
		record Probe(String label, String url) {
		}
		var probes = List.of(new Probe("v1 listado", V1 + ".json?rows=1"),
				new Probe("v1 convocatoria", V1 + "/convocatoria.json?rows=1"),
				new Probe("v1 resolucion", V1 + "/resolucion.json?rows=1"),
				new Probe("v1 gestor", V1 + "/gestor.json?rows=1"),
				new Probe("v1 distinct", V1 + "/distinct.json?field=tipoInstrumento"),
				new Probe("v1 groupBy", V1 + "/groupBy.json?fields=tipoInstrumento"),
				new Probe("v2 listado", V2 + ".json?rows=1"),
				new Probe("v2 convocatoria", V2 + "/convocatoria.json?rows=1"),
				new Probe("v2 resolucion", V2 + "/resolucion.json?rows=1"),
				new Probe("v2 concesion (sin documentar)", V2 + "/concesion.json?rows=1"),
				new Probe("v2 concesion/{id} del Swagger", V2 + "/concesion/7.json"),
				new Probe("v2 organization", V2 + "/organization.json?rows=1"));

		var rows = new ArrayList<List<String>>();
		for (Probe probe : probes) {
			Response response = api.tryGet(probe.url());
			String envelope = "-";
			String total = "-";
			if (response.status() == 200 && response.isJson()) {
				JsonNode body = response.json();
				if (body.has("records")) {
					envelope = "{page,pageSize,totalRecords,records}";
					total = text(body, "totalRecords");
				}
				else if (body.has("result")) {
					envelope = "{totalCount,start,rows,result}";
					total = text(body, "totalCount");
				}
				else {
					envelope = "documento";
				}
			}
			rows.add(List.of(probe.label(), String.valueOf(response.status()), envelope, total));
			pause();
		}
		table(ID, List.of("recurso", "estado", "envoltorio", "total"), rows);

		// El mismo tag sirve dos envoltorios distintos, y el que la documentación no menciona (concesion) existe.
		Response concessions = api.get(V2 + "/concesion.json?rows=1");
		assertThat(concessions.status()).isEqualTo(200);
		assertThat(concessions.json().has("records")).isTrue();
		Response documentedDetail = api.tryGet(V2 + "/concesion/7.json");
		metric(ID, "\n- `concesion.json` **no está en el Swagger** y responde 200; `concesion/{id}` sí lo está y su "
				+ "valor de ejemplo documentado (`id=7`) responde " + documentedDetail.status() + ".");
	}

	// -------------------------------------------------------------------------------------------------------
	// §2 — Paginación: dos parámetros de tamaño, uno con tope y otro sin él, y un solape que nada anuncia
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(2)
	void paginationRules() {
		heading(ID, "§2 Paginación: `rows` topa, `pageSize` no, y mezclarlos solapa páginas");

		var rows = new ArrayList<List<String>>();
		for (String query : List.of("rows=500", "rows=2000", "pageSize=500", "pageSize=5000", "pageSize=30000")) {
			Response response = api.get(V1 + ".json?" + query);
			rows.add(List.of("`" + query + "`", String.valueOf(response.json().path("records").size()),
					String.valueOf(response.body().length() / 1024) + " KB"));
			pause();
		}
		table(ID, List.of("petición", "registros", "tamaño"), rows);

		int capped = api.get(V1 + ".json?rows=2000").json().path("records").size();
		int uncapped = api.get(V1 + ".json?pageSize=5000").json().path("records").size();
		assertThat(capped).as("`rows` sigue topando en 500, como el resto de la sede").isEqualTo(ROWS);
		assertThat(uncapped).as("`pageSize` no tiene tope: devuelve lo que se le pida").isEqualTo(5000);

		// El solape: `page` se calcula con `pageSize` (50 por defecto) aunque el tamaño lo haya fijado `rows`.
		List<String> first = ids(api.get(V1 + ".json?rows=100&page=1"));
		List<String> second = ids(api.get(V1 + ".json?rows=100&page=2"));
		var overlap = new TreeSet<>(first);
		overlap.retainAll(second);
		metric(ID, "\n- `rows=100&page=1` y `rows=100&page=2` comparten **" + overlap.size() + "** registros de 100: "
				+ "el desplazamiento lo calcula `pageSize` (50 por defecto) y el tamaño lo fija `rows`.");
		assertThat(overlap).as("mezclar `rows` y `page` solapa páginas sin avisar").isNotEmpty();

		List<String> byPageSize = ids(api.get(V1 + ".json?pageSize=100&page=2"));
		assertThat(byPageSize).as("con `pageSize` solo, la página 2 empieza donde acaba la 1").doesNotContainAnyElementsOf(first);

		// El orden por defecto sí es estable aquí, al revés que en el presupuesto (S3.2 §2).
		var orders = new TreeSet<String>();
		for (int i = 0; i < 10; i++) {
			orders.add(String.join(",", ids(api.get(V1 + ".json?rows=5"))));
			pause();
		}
		metric(ID, "- Diez peticiones idénticas al listado dan **" + orders.size() + "** ordenación(es) distinta(s). "
				+ "En el presupuesto eran dos (S3.2 §2).");
		assertThat(orders).hasSize(1);

		// `sort` se aplica de verdad, al revés que en OCDS (S3.1).
		List<String> descending = ids(api.get(V1 + ".json?rows=3&sort=" + ZaragozaSpikeClient.enc("id desc")));
		assertThat(descending).isSortedAccordingTo((a, b) -> Integer.compare(Integer.parseInt(b), Integer.parseInt(a)));
		Response unknownSort = api.tryGet(V1 + ".json?rows=3&sort=" + ZaragozaSpikeClient.enc("noexiste asc"));
		metric(ID, "- `sort` se aplica de verdad (al revés que en OCDS, S3.1) y un campo inexistente responde **"
				+ unknownSort.status() + "**, no un orden silencioso.");
		assertThat(unknownSort.status()).isEqualTo(400);
	}

	// -------------------------------------------------------------------------------------------------------
	// §3 — La proyección: la llave de ADR-018, y una trampa con solución
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(3)
	void projection() {
		heading(ID, "§3 `fl`: recorta de verdad en la v1, vacía el tercer nivel y devuelve `{}` en la v2");

		JsonNode projected = api.get(V1 + "/resolucion.json?rows=1&fl="
				+ ZaragozaSpikeClient.enc("id,importeConcedido,fechaConcesion,expediente")).json();
		JsonNode record = projected.path("result").path(0);
		metric(ID, "- Proyección de cuatro campos escalares sobre `/resolucion`: llegan **" + record.size() + "**.");
		assertThat(record.size()).isEqualTo(4);
		assertThat(record.has("adjudicatario")).as("la proyección es la garantía: el nombre no se descarga").isFalse();

		// El tercer nivel llega vacío, como los anidados de `registro-licencia` (S2.4).
		JsonNode nested = api.get(V1 + "/convocatoria.json?rows=1&fl="
				+ ZaragozaSpikeClient.enc("id,lineaEstrategica")).json().path("result").path(0);
		assertThat(nested.path("lineaEstrategica").path("lineaAuxiliar").size())
				.as("el tercer nivel llega vacío, como en S2.4").isZero();

		// Y con ruta con punto llega entero. No está documentado en el Swagger.
		JsonNode dotted = api.get(V1 + "/convocatoria.json?rows=1&fl="
				+ ZaragozaSpikeClient.enc("id,lineaEstrategica.lineaAuxiliar")).json().path("result").path(0);
		assertThat(dotted.path("lineaEstrategica").path("lineaAuxiliar").path("title").isString())
				.as("`fl` acepta rutas con punto y entonces el tercer nivel llega entero").isTrue();
		metric(ID, "- `fl=lineaEstrategica` vacía el tercer nivel; `fl=lineaEstrategica.lineaAuxiliar` lo trae entero. "
				+ "La ruta con punto **no está documentada** y es lo que hace viable la proyección de convocatorias.");

		// El recorte es por subárbol y no por campo: pedir un hijo trae al padre entero.
		JsonNode subtree = api.get(V1 + "/resolucion.json?rows=1&fl=" + ZaragozaSpikeClient.enc("id,convocatoria.id"))
				.json().path("result").path(0);
		metric(ID, "- `fl=convocatoria.id` devuelve la convocatoria entera (**" + subtree.path("convocatoria").size()
				+ "** campos): el recorte es por subárbol, no por campo.");
		assertThat(subtree.path("convocatoria").size()).isGreaterThan(1);

		// En la v2 la proyección no es que no recorte: destruye la respuesta.
		Response v2 = api.get(V2 + "/concesion.json?rows=2&fl=" + ZaragozaSpikeClient.enc("id,importeConcedido"));
		metric(ID, "- La misma proyección sobre `ayuda-subvencion-v2/concesion` devuelve **`" + v2.body().strip()
				+ "`**: no un recorte, un documento vacío.");
		assertThat(v2.body().strip()).isEqualTo("{}");
	}

	// -------------------------------------------------------------------------------------------------------
	// §4 — El censo: la versión vieja es la completa, y la nueva esconde dos años
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(4)
	void censusIsTheOldVersion() {
		heading(ID, "§4 El censo completo es `ayuda-subvencion/resolucion`, no la v2");

		long started = System.currentTimeMillis();
		int declared = sweepV1();
		sweepMillis = System.currentTimeMillis() - started;
		metric(ID, "- Barrido de `/resolucion` con `sort=id asc` y proyección: **" + sweepRequests + "** peticiones, **"
				+ v1Ids.size() + "** identificadores distintos sobre un `totalCount` de **" + declared + "**, "
				+ (sweepBytes / 1048576) + " MB en " + (sweepMillis / 1000) + " s.");
		assertThat(v1Ids).as("el barrido por id no pierde ni repite (se cuenta por ids, no por filas)").hasSize(declared);

		int v2Declared = sweepV2Concessions();
		metric(ID, "- Barrido de `ayuda-subvencion-v2/concesion`: **" + v2Ids.size() + "** identificadores distintos "
				+ "sobre un `totalRecords` de **" + v2Declared + "**.");
		assertThat(v2Ids).hasSize(v2Declared);

		var onlyV1 = new TreeSet<>(v1Ids);
		onlyV1.removeAll(v2Ids);
		var onlyV2 = new TreeSet<>(v2Ids);
		onlyV2.removeAll(v1Ids);
		metric(ID, "- Solo en la v1: **" + onlyV1.size() + "**. Solo en la v2: **" + onlyV2.size() + "**. "
				+ "La v2 es un **subconjunto estricto**, y lo que esconde son los ejercicios más antiguos.");
		assertThat(onlyV2).as("la v2 no aporta ni un registro propio").isEmpty();
		assertThat(onlyV1).as("la v2 esconde los primeros ejercicios").isNotEmpty();

		table(ID, List.of("año de concesión", "concesiones", "importe concedido (€)"),
				yearCount.entrySet().stream()
						.map(e -> List.of(e.getKey(), String.valueOf(e.getValue()),
								String.format("%,.2f", yearAmount.getOrDefault(e.getKey(), 0L) / 100.0)))
						.toList());
	}

	// -------------------------------------------------------------------------------------------------------
	// §5 — Dato personal: la fuente enmascara el identificador y publica el nombre
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(5)
	void personalDataInTheSource() {
		heading(ID, "§5 La fuente se contradice: enmascara el NIF y publica el nombre y el DNI");

		// Se pide una sola vez SIN proyección, para medir qué hay. Nada de esto se guarda ni se imprime.
		int scanned = 0;
		var nameKind = new TreeMap<String, Integer>();
		var beneficiaryKind = new TreeMap<String, Integer>();
		for (int start = 0; start < 47000; start += ROWS) {
			Response response = api.get(V1 + "/resolucion.json?rows=" + ROWS + "&start=" + start + "&sort="
					+ ZaragozaSpikeClient.enc("id asc") + "&fl="
					+ ZaragozaSpikeClient.enc("id,title,expediente,adjudicatario"));
			JsonNode result = response.json().path("result");
			if (result.isEmpty()) {
				break;
			}
			for (JsonNode node : result) {
				scanned++;
				JsonNode grantee = node.path("adjudicatario");
				nameKind.merge(classify(text(grantee, "nombre"), PERSON_PLACEHOLDER), 1, Integer::sum);
				beneficiaryKind.merge(classify(text(grantee, "beneficiario"), NAME_PLACEHOLDER), 1, Integer::sum);
				countIdentities("title", text(node, "title"));
				countIdentities("expediente", text(node, "expediente"));
				countIdentities("adjudicatario.nombre", text(grantee, "nombre"));
			}
			pause();
		}
		metric(ID, "- Registros mirados: **" + scanned + "**.");
		counts(ID, "`adjudicatario.nombre`", nameKind);
		counts(ID, "`adjudicatario.beneficiario`", beneficiaryKind);
		counts(ID, "identidades encontradas (letra de control comprobada)", identity);

		assertThat(nameKind.getOrDefault("real", 0))
				.as("la fuente publica nombres reales de persona en un campo estructural").isPositive();
		assertThat(identity.getOrDefault("title DNI válido", 0))
				.as("y publica DNI válidos dentro del texto del título").isPositive();
		assertThat(identity.getOrDefault("expediente DNI válido", 0))
				.as("el expediente, en cambio, no lleva identidad").isZero();

		// Todos los DNI que aparecen tienen letra de control válida: no son falsos positivos.
		int valid = identity.getOrDefault("title DNI válido", 0);
		int invalid = identity.getOrDefault("title DNI con letra incorrecta", 0);
		metric(ID, "- De las coincidencias con forma de DNI en `title`, **" + valid + "** tienen letra de control "
				+ "correcta y **" + invalid + "** no. No son falsos positivos: son documentos de identidad.");
	}

	// -------------------------------------------------------------------------------------------------------
	// §6 — El directorio de beneficiarios: la fuente sí anonimiza aquí, y repite identificadores
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(6)
	void beneficiaryDirectory() {
		heading(ID, "§6 El directorio de beneficiarios anonimiza a la persona física… y repite ids");

		int declared = 0;
		int records = 0;
		var withContact = new TreeMap<String, Integer>();
		var titles = new TreeMap<String, Set<String>>();
		for (int page = 1; page <= 40; page++) {
			Response response = api.get(V2 + "/organization.json?pageSize=1000&page=" + page + "&sort="
					+ ZaragozaSpikeClient.enc("id asc"));
			JsonNode body = response.json();
			declared = body.path("totalRecords").asInt();
			JsonNode items = body.path("records");
			for (JsonNode node : items) {
				records++;
				String id = text(node, "id");
				String classification = classification(node);
				beneficiaryClass.put(id, classification);
				titles.computeIfAbsent(classification, key -> new TreeSet<>()).add(text(node, "title"));
				for (String field : List.of("streetAddress", "postalCode", "contactPointTelephone",
						"contactPointEmail", "url")) {
					if (!text(node, field).isBlank()) {
						withContact.merge(classification + " · " + field, 1, Integer::sum);
					}
				}
			}
			if (items.size() < 1000) {
				break;
			}
			pause();
		}
		metric(ID, "- El directorio declara **" + declared + "** registros y trae **" + beneficiaryClass.size()
				+ "** identificadores distintos: **" + (records - beneficiaryClass.size()) + "** vienen repetidos.");
		assertThat(beneficiaryClass.size()).isLessThan(records);

		var byClass = new TreeMap<String, Integer>();
		beneficiaryClass.values().forEach(c -> byClass.merge(c, 1, Integer::sum));
		counts(ID, "clasificación", byClass);

		Set<String> personTitles = titles.getOrDefault("personas-fisicas", Set.of());
		metric(ID, "- Las entidades clasificadas como `personas-fisicas` tienen **" + personTitles.size()
				+ "** título(s) distinto(s): siempre «" + PERSONAL_DATA + "». Aquí la fuente **sí** anonimiza.");
		assertThat(personTitles).containsExactly(PERSONAL_DATA);

		counts(ID, "datos de contacto publicados", withContact);
		assertThat(withContact.keySet().stream().filter(k -> k.startsWith("personas-fisicas")).toList())
				.as("de una persona física no se publica ni dirección ni teléfono ni correo").isEmpty();
	}

	// -------------------------------------------------------------------------------------------------------
	// §7 — Las dos señales de persona física no coinciden del todo
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(7)
	void twoSignalsDisagree() {
		heading(ID, "§7 Persona física: dos señales, y no dicen lo mismo en 1,5 % de los casos");

		var cross = new LinkedHashMap<String, Integer>();
		int naturalByMask = 0;
		int naturalByClass = 0;
		int naturalByEither = 0;
		for (Integer id : v2Ids) {
			String nif = v2NifKind.getOrDefault(id, "-");
			String classification = beneficiaryClass.getOrDefault(v2Beneficiary.get(id), "(sin ficha)");
			cross.merge(nif + " · " + classification, 1, Integer::sum);
			boolean masked = "enmascarado".equals(nif);
			boolean person = "personas-fisicas".equals(classification);
			naturalByMask += masked ? 1 : 0;
			naturalByClass += person ? 1 : 0;
			naturalByEither += (masked || person) ? 1 : 0;
		}
		counts(ID, "`nifcif` · clasificación del beneficiario", cross);
		metric(ID, "- Persona física por NIF enmascarado: **" + naturalByMask + "**. Por clasificación: **"
				+ naturalByClass + "**. Por cualquiera de las dos: **" + naturalByEither + "** de " + v2Ids.size()
				+ " concesiones (" + String.format("%.1f", 100.0 * naturalByEither / v2Ids.size()) + " %).");
		assertThat(naturalByEither).isGreaterThan(Math.max(naturalByMask, naturalByClass));

		long unknown = v2Ids.stream().filter(id -> !beneficiaryClass.containsKey(v2Beneficiary.get(id))).count();
		metric(ID, "- Concesiones cuyo beneficiario no está en el directorio: **" + unknown + "**.");
		assertThat(unknown).isZero();
	}

	// -------------------------------------------------------------------------------------------------------
	// §8 — Las convocatorias: baratas, sin identidad, y con una segunda copia del conjunto dentro
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(8)
	void calls() {
		heading(ID, "§8 Convocatorias: 1.589 en cuatro peticiones, y una trampa de 21 MB");

		int declared = 0;
		int seen = 0;
		long bytes = 0;
		int requests = 0;
		var years = new TreeMap<String, Integer>();
		long budget = 0;
		var ids = new TreeSet<Integer>();
		for (int start = 0; start < 5000; start += ROWS) {
			Response response = api.get(V1 + "/convocatoria.json?rows=" + ROWS + "&start=" + start + "&sort="
					+ ZaragozaSpikeClient.enc("id asc") + "&fl=" + ZaragozaSpikeClient.enc(CALL_FIELDS));
			requests++;
			bytes += response.body().length();
			JsonNode body = response.json();
			declared = body.path("totalCount").asInt();
			JsonNode result = body.path("result");
			for (JsonNode node : result) {
				seen++;
				ids.add(node.path("id").asInt());
				years.merge(text(node, "ejercicioClave"), 1, Integer::sum);
				budget += Math.round(node.path("presupuesto").asDouble() * 100);
				countIdentities("convocatoria.title", text(node, "title"));
			}
			if (result.size() < ROWS) {
				break;
			}
			pause();
		}
		metric(ID, "- **" + ids.size() + "** convocatorias distintas de un `totalCount` de **" + declared + "** en **"
				+ requests + "** peticiones y " + (bytes / 1024) + " KB. Presupuesto declarado: **"
				+ String.format("%,.2f", budget / 100.0) + " €**.");
		assertThat(ids).hasSize(declared);
		assertThat(seen).isEqualTo(declared);
		counts(ID, "ejercicio", years);

		// Sin proyección, la convocatoria trae dentro todas sus resoluciones: el conjunto entero, otra vez.
		Response whole = api.get(V1 + "/convocatoria.json?rows=2");
		JsonNode first = whole.json().path("result").path(0);
		metric(ID, "- Sin `fl`, cada convocatoria trae su array `resolucion[]` dentro (aquí **"
				+ first.path("resolucion").size() + "** en el primer registro): es una **segunda copia** del "
				+ "conjunto de concesiones, con los nombres y los DNI incluidos, y son 21 MB.");
		assertThat(first.path("resolucion").isArray()).isTrue();
	}

	// -------------------------------------------------------------------------------------------------------
	// §9 — Frescura y eje incremental: no hay marca de modificación, pero el id sirve de marca de agua
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(9)
	void freshnessAndWatermark() {
		heading(ID, "§9 Frescura: sin `Last-Modified`, sin `ETag`, sin campo de modificación");

		var rows = new ArrayList<List<String>>();
		for (String url : List.of(V1 + "/resolucion.json?rows=1", V1 + "/convocatoria.json?rows=1",
				V2 + "/concesion.json?rows=1", V2 + "/organization.json?rows=1")) {
			Response response = api.get(url);
			rows.add(List.of("`" + url.replace(SEDE, "") + "`", String.valueOf(response.header("Last-Modified")),
					String.valueOf(response.header("ETag"))));
			pause();
		}
		table(ID, List.of("recurso", "Last-Modified", "ETag"), rows);

		// El eje incremental barato: el id es creciente y FIQL lo filtra.
		int watermark = v1Ids.isEmpty() ? 55000 : v1Ids.stream().mapToInt(Integer::intValue).max().orElse(55000) - 400;
		Response above = api.get(V1 + "/resolucion.json?rows=" + ROWS + "&sort=" + ZaragozaSpikeClient.enc("id asc")
				+ "&fl=id&q=" + ZaragozaSpikeClient.enc("id=gt=" + watermark));
		metric(ID, "- `q=id=gt=" + watermark + "` devuelve **" + above.json().path("totalCount").asInt()
				+ "** registros: el identificador es creciente y FIQL lo filtra, así que la ingesta diaria no "
				+ "vuelve a barrer las 46.925.");
		assertThat(above.json().path("totalCount").asInt()).isPositive();

		// La fecha también admite FIQL, y sirve para capturar lo que cambie sin id nuevo.
		Response byDate = api.get(V1 + "/resolucion.json?rows=1&fl=id&q="
				+ ZaragozaSpikeClient.enc("fechaConcesion=ge=2026-01-01T00:00:00"));
		metric(ID, "- `q=fechaConcesion=ge=2026-01-01T00:00:00` devuelve **"
				+ byDate.json().path("totalCount").asInt() + "**: la fecha también filtra.");
		assertThat(byDate.status()).isEqualTo(200);
	}

	// -------------------------------------------------------------------------------------------------------
	// §10 — Fixtures: siempre redactados (regla 22)
	// -------------------------------------------------------------------------------------------------------

	@Test
	@Order(10)
	void fixtures() {
		heading(ID, "§10 Fixtures");

		Set<String> personal = Set.of("nombre", "beneficiario", "title", "adjudicatarioTitle", "adjudicatarioId",
				"streetAddress", "postalCode", "contactPointTelephone", "contactPointEmail", "nifcif");

		var saved = new ArrayList<List<String>>();
		record Fixture(String name, String url, boolean redact) {
		}
		var fixtures = List.of(
				new Fixture("resolucion-rows2-fl.json", V1 + "/resolucion.json?rows=2&sort="
						+ ZaragozaSpikeClient.enc("id asc") + "&fl=" + ZaragozaSpikeClient.enc(CONCESSION_FIELDS), true),
				new Fixture("resolucion-rows2-full.json", V1 + "/resolucion.json?rows=2&sort="
						+ ZaragozaSpikeClient.enc("id asc"), true),
				new Fixture("convocatoria-rows2-fl.json", V1 + "/convocatoria.json?rows=2&sort="
						+ ZaragozaSpikeClient.enc("id asc") + "&fl=" + ZaragozaSpikeClient.enc(CALL_FIELDS), true),
				new Fixture("concesion-rows2.json", V2 + "/concesion.json?pageSize=2&page=1&sort="
						+ ZaragozaSpikeClient.enc("id asc"), true),
				new Fixture("organization-rows3.json", V2 + "/organization.json?pageSize=3&page=1&sort="
						+ ZaragozaSpikeClient.enc("id asc"), true),
				new Fixture("concesion-fl-vacio.json", V2 + "/concesion.json?rows=2&fl=id", false),
				new Fixture("resolucion-beyond.json", V1 + "/resolucion.json?rows=2&start=999000&sort="
						+ ZaragozaSpikeClient.enc("id asc") + "&fl=id", false));

		for (Fixture fixture : fixtures) {
			Response response = api.get(fixture.url());
			if (fixture.redact()) {
				SpikeFixtures.saveRedacted(FIXTURES, fixture.name(), response.body(), personal);
			}
			else {
				SpikeFixtures.save(FIXTURES, fixture.name(), response.body());
			}
			SpikeFixtures.saveHeaders(FIXTURES, fixture.name().replace(".json", ".headers"), response.status(),
					response.headers());
			saved.add(List.of("`" + fixture.name() + "`", String.valueOf(response.status()),
					fixture.redact() ? "redactado" : "tal cual", response.body().length() + " B"));
			pause();
		}
		table(ID, List.of("fixture", "estado", "texto", "tamaño"), saved);
		metric(ID, "\n- Los campos que se sustituyen por el marcador son: " + new TreeSet<>(personal)
				+ ". Un fixture de esta fuente **no puede** guardarse sin redactar (regla 22).");
	}

	// --- utilidades ----------------------------------------------------------------------------------------

	private int sweepV1() {
		int declared = 0;
		for (int start = 0; start < 60000; start += ROWS) {
			long began = System.nanoTime();
			Response response = api.get(V1 + "/resolucion.json?rows=" + ROWS + "&start=" + start + "&sort="
					+ ZaragozaSpikeClient.enc("id asc") + "&fl=" + ZaragozaSpikeClient.enc(CONCESSION_FIELDS));
			sweepRequests++;
			sweepBytes += response.body().length();
			JsonNode body = response.json();
			declared = body.path("totalCount").asInt();
			JsonNode result = body.path("result");
			for (JsonNode node : result) {
				v1Ids.add(node.path("id").asInt());
				String year = year(text(node, "fechaConcesion"));
				yearCount.merge(year, 1, Integer::sum);
				yearAmount.merge(year, Math.round(node.path("importeConcedido").asDouble() * 100), Long::sum);
			}
			if (result.size() < ROWS) {
				break;
			}
			pause();
			assertThat(System.nanoTime() - began).isPositive();
		}
		return declared;
	}

	private int sweepV2Concessions() {
		int declared = 0;
		for (int page = 1; page <= 60; page++) {
			Response response = api.get(V2 + "/concesion.json?pageSize=2000&page=" + page + "&sort="
					+ ZaragozaSpikeClient.enc("id asc"));
			JsonNode body = response.json();
			declared = body.path("totalRecords").asInt();
			JsonNode items = body.path("records");
			for (JsonNode node : items) {
				int id = node.path("id").asInt();
				v2Ids.add(id);
				v2Beneficiary.put(id, text(node, "beneficiario"));
				v2NifKind.put(id, nifKind(text(node, "nifcif")));
			}
			if (items.size() < 2000) {
				break;
			}
			pause();
		}
		return declared;
	}

	/** Clase del identificador fiscal publicado, sin guardarlo nunca. */
	static String nifKind(String value) {
		String trimmed = value == null ? "" : value.strip();
		if (trimmed.isEmpty()) {
			return "vacío";
		}
		if (NIF_PLACEHOLDER.equals(trimmed)) {
			return "marcador";
		}
		if (MASKED.matcher(trimmed).matches()) {
			return "enmascarado";
		}
		if (LEGAL_NIF.matcher(trimmed).matches()) {
			return "NIF de persona jurídica";
		}
		return "otro";
	}

	/** Marcador constante, vacío o texto real: lo único que se dice de un valor que puede ser una persona. */
	static String classify(String value, String placeholder) {
		String trimmed = value == null ? "" : value.strip();
		if (trimmed.isEmpty()) {
			return "vacío";
		}
		if (placeholder.equals(trimmed)) {
			return "marcador";
		}
		if (PERSONAL_DATA.equals(trimmed)) {
			return "«" + PERSONAL_DATA + "»";
		}
		return "real";
	}

	static String classification(JsonNode node) {
		String raw = text(node, "classification");
		int slash = raw.lastIndexOf('/');
		String tail = slash < 0 ? raw : raw.substring(slash + 1);
		return tail.replace(">", "").strip();
	}

	/** Cuenta documentos de identidad por campo, comprobando la letra de control. Nunca guarda el valor. */
	static void countIdentities(String field, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		Matcher dni = DNI.matcher(value);
		while (dni.find()) {
			boolean valid = checkLetter(Integer.parseInt(dni.group(1))) == Character.toUpperCase(dni.group(2).charAt(0));
			identity.merge(field + (valid ? " DNI válido" : " DNI con letra incorrecta"), 1, Integer::sum);
		}
		Matcher nie = NIE.matcher(value);
		while (nie.find()) {
			int prefix = "XYZ".indexOf(Character.toUpperCase(nie.group(1).charAt(0)));
			int number = Integer.parseInt(prefix + nie.group(2));
			boolean valid = checkLetter(number) == Character.toUpperCase(nie.group(3).charAt(0));
			identity.merge(field + (valid ? " NIE válido" : " NIE con letra incorrecta"), 1, Integer::sum);
		}
	}

	static char checkLetter(int number) {
		return CHECK_LETTERS.charAt(number % 23);
	}

	static String year(String timestamp) {
		return timestamp == null || timestamp.length() < 4 ? "(sin fecha)" : timestamp.substring(0, 4);
	}

	private static List<String> ids(Response response) {
		var out = new ArrayList<String>();
		JsonNode body = response.json();
		for (JsonNode node : body.has("records") ? body.path("records") : body.path("result")) {
			out.add(text(node, "id"));
		}
		return out;
	}

	private static void pause() {
		try {
			Thread.sleep(PAUSE.toMillis());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
