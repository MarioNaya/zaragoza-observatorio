package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.paths;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * S3.2 — Presupuesto de gastos ({@code presupuesto/gasto-corriente}): la segunda fuente de la fase 3 y la
 * <b>única</b> con gasto ejecutado. Informe: docs/spikes/S3.2-presupuesto-ingesta.md.
 * <p>
 * S3.1 dejó demostrado que OCDS publica <b>licitado y adjudicado, nunca pagado</b> (0 {@code planning}, 0
 * {@code implementation}), y ADR-003 §2 ya lo anticipaba: {@code executed} solo puede salir del presupuesto. S0.6
 * inventarió la fuente en una línea —«1.251 partidas en el snapshot actual, 140 fechas de snapshot»— y S0.5 midió
 * de ella tres cosas sueltas: envoltorio {@code {totalCount,result}}, tope de {@code rows} 500 y {@code fecha} en
 * {@code yyyyMMdd}. Todo lo demás está por medir, y las cuatro fuentes ya ingeridas dicen qué preguntar:
 * <ol>
 * <li><b>Qué es un registro y cuál es su unidad.</b> Aquí no hay un censo de entidades sino <b>instantáneas
 * datadas</b> del mismo presupuesto: la misma partida aparece una vez por cada fecha. Sin saber si el pasado se
 * reescribe no se puede decidir si la ingesta es incremental o un barrido perpetuo.</li>
 * <li><b>Por qué eje se barre sin perder registros</b> (S2.2 §10, S2.4): el orden por defecto de la sede no está
 * documentado, y se comprueba contando <b>identificadores distintos</b>, no filas.</li>
 * <li><b>Dato personal</b> (regla 22, ADR-012, ADR-016, ADR-017). Cuatro fuentes, cuatro respuestas distintas. Aquí
 * el texto es administrativo (nombres de partida, programa y órgano), pero eso <b>se mide</b>: se cuentan patrones,
 * nunca se imprime el valor.</li>
 * <li><b>Coste de la ingesta</b> (ADR-004, ADR-009): 140 instantáneas × ~1.000 partidas no caben en
 * {@code handle(RawPage)} si hay que pedirlas una a una.</li>
 * <li><b>Qué sostiene el documento</b> (regla 6). La fuente publica ocho importes por partida, del crédito inicial
 * al pago neto. Hay que saber cuáles son y qué relación guardan antes de exponer ninguno como «gasto».</li>
 * </ol>
 * Y una pregunta propia: los tres resúmenes anuales ({@code gastado-resumen}, {@code organo-resumen},
 * {@code programa-resumen}) publican series 2015–2026 de {@code presupuestado}/{@code gastado}. Si son derivables
 * de las instantáneas, no se ingieren; si no cuadran, la discrepancia es un hecho publicable.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S32BudgetSpike {

	static final String ID = "S3.2-presupuesto-ingesta";
	static final String FIXTURES = "budget";

	/** Tope de página de la sede (S0.5, regla 18). */
	static final int ROWS = 500;

	static final String BUDGET = SEDE + "/presupuesto";
	static final String EXPENSE = BUDGET + "/gasto-corriente";
	static final String DATES = EXPENSE + "/fecha.json";
	static final String REVENUE = BUDGET + "/ingreso-corriente";
	static final String SPENT_SUMMARY = BUDGET + "/gastado-resumen.json";
	static final String BODY_SUMMARY = BUDGET + "/organo-resumen.json";
	static final String PROGRAM_SUMMARY = BUDGET + "/programa-resumen.json";

	/** Fixture grabado por S0.6 el 2026-09-05: tres partidas completas de la instantánea 20260831. */
	static final Path S06_FIXTURE = SpikeFixtures.FIXTURES_ROOT
			.resolve("inventory/sede_servicio_presupuesto_gasto-corriente.json_rows_3_srsname_wgs84.json");

	/** Pausa entre peticiones del barrido: la fuente es pública y no se le hace un barrido a pelo. */
	static final Duration PAUSE = Duration.ofMillis(40);

	static final JsonMapper JSON = JsonMapper.shared();

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(180));

	/** Las fechas de instantánea publicadas, de la más antigua a la más reciente. */
	static final List<String> dates = new ArrayList<>();

	/** Resumen de cada instantánea barrida, en el mismo orden que {@link #dates}. */
	static final List<Snapshot> sweep = new ArrayList<>();

	/** Para cada `concepto`, en cuántas instantáneas aparece y en cuántos años distintos. */
	static final Map<String, Integer> conceptSnapshots = new TreeMap<>();

	static final Map<String, Set<String>> conceptYears = new TreeMap<>();

	/** Para cada eje (órgano+programa+epígrafe), los años en los que aparece. */
	static final Map<String, Set<String>> axisYears = new TreeMap<>();

	/** Recuento de coincidencias de cada patrón personal por campo (regla 22). */
	static final Map<String, Map<String, Integer>> textPatterns = new TreeMap<>();

	/** Caminos JSON vistos en el barrido y en cuántos registros aparecen, por año. */
	static final Map<String, Map<String, Integer>> fieldsByYear = new TreeMap<>();

	static long sweepRequests;

	static long sweepBytes;

	static long sweepMillis;

	// --- modelo compacto --------------------------------------------------------------------------------

	/** Lo que se retiene de cada instantánea: recuentos y sumas, nunca los ~1.000 registros. */
	record Snapshot(String date, int totalCount, int rows, int distinctIds, int distinctConcepts,
			Map<String, Double> money, Map<Integer, Integer> chapters, long bytes, long millis) {

		String year() {
			return date.substring(0, 4);
		}

		double of(String field) {
			return money.getOrDefault(field, 0.0);
		}
	}

	/** Los ocho importes que publica cada partida, en el orden en que los publica la fuente. */
	static final List<String> AMOUNTS = List.of("creditoInicial", "creditoModificacion", "creditoDefinitivo",
			"gastoComprometido", "obligacionNeta", "pagoNeto", "obligacionPendientePago", "remanenteDeCredito");

	/** Campos de texto de la partida. Se examinan enteros; no se guarda ni se imprime su contenido. */
	static final List<String> TEXT_FIELDS = List.of("area", "partida", "capitulo", "epigrafe", "programa", "organo",
			"concepto");

	// --- utilidades -------------------------------------------------------------------------------------

	static String url(String base, String... keyValues) {
		var params = new LinkedHashMap<String, String>();
		for (int i = 0; i < keyValues.length; i += 2) {
			params.put(keyValues[i], keyValues[i + 1]);
		}
		return ZaragozaSpikeClient.url(base, params);
	}

	static String snapshotUrl(String date) {
		return EXPENSE + "/fecha/" + date + ".json";
	}

	static List<JsonNode> results(Response response) {
		if (response.failed() || response.status() != 200 || response.body().isBlank()) {
			return List.of();
		}
		var out = new ArrayList<JsonNode>();
		response.json().path("result").forEach(out::add);
		return out;
	}

	static int totalCount(Response response) {
		return response.status() == 200 ? response.json().path("totalCount").asInt(-1) : -1;
	}

	static void pause() {
		try {
			Thread.sleep(PAUSE);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static String euros(double value) {
		return String.format("%,.2f", value);
	}

	// --- patrones de dato personal (regla 22: se cuentan, nunca se imprimen) -----------------------------

	static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

	static {
		PATTERNS.put("DNI", Pattern.compile("\\b[0-9]{8}\\s?-?\\s?[A-Za-z]\\b"));
		PATTERNS.put("NIE", Pattern.compile("\\b[XYZxyz]\\s?-?\\s?[0-9]{7}\\s?-?\\s?[A-Za-z]\\b"));
		PATTERNS.put("CIF", Pattern.compile(
				"\\b[ABCDEFGHJNPQRSUVWabcdefghjnpqrsuvw]\\s?-?\\s?[0-9]{7}\\s?-?\\s?[0-9A-Ja-j]\\b"));
		PATTERNS.put("correo", Pattern.compile("[\\w.+-]+@[\\w-]+\\.[A-Za-z]{2,}"));
		PATTERNS.put("telefono", Pattern.compile("\\b[6789][0-9]{8}\\b"));
		PATTERNS.put("tratamiento", Pattern.compile("(?i)\\b(D\\.|DÑA\\.|DOÑA|SR\\.|SRA\\.|DON)\\s*[A-ZÁÉÍÓÚÑ]"));
		// Fórmulas con las que una partida presupuestaria nombra a una persona física: pensiones y legados
		// heredados del presupuesto antiguo. Se cuentan, no se leen.
		PATTERNS.put("persona", Pattern.compile("(?i)\\b(VIUDA DE|VDA\\.?\\s?DE|HEREDEROS DE|HDROS)\\b"));
	}

	/** En qué instantáneas aparece cada patrón sensible, para saber si es cosa de un año o de todos. */
	static final Map<String, TreeSet<String>> patternDates = new TreeMap<>();

	/** Fecha de la instantánea que se está barriendo, para anotar dónde cae cada coincidencia. */
	static String scanning = "";

	/** Letras del DNI por resto módulo 23: distingue un DNI de verdad de ocho dígitos y una letra cualquiera. */
	static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

	static void scanText(JsonNode node) {
		for (String field : TEXT_FIELDS) {
			String value = text(node, field);
			if (value.isBlank()) {
				continue;
			}
			Map<String, Integer> byPattern = textPatterns.computeIfAbsent(field, k -> new TreeMap<>());
			byPattern.merge("noVacios", 1, Integer::sum);
			PATTERNS.forEach((name, pattern) -> {
				if (pattern.matcher(value).find()) {
					byPattern.merge(name, 1, Integer::sum);
					patternDates.computeIfAbsent(field + "/" + name, k -> new TreeSet<>()).add(scanning);
				}
			});
			var dni = PATTERNS.get("DNI").matcher(value);
			while (dni.find()) {
				String match = dni.group().replaceAll("[^0-9A-Za-z]", "");
				int number = Integer.parseInt(match.substring(0, 8));
				char letter = Character.toUpperCase(match.charAt(match.length() - 1));
				byPattern.merge(DNI_LETTERS.charAt(number % 23) == letter ? "dniConLetraValida" : "dniConLetraInvalida",
						1, Integer::sum);
			}
		}
	}

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
		metric(ID, "Fuente: " + EXPENSE + ".json (dataset 336 «Ejecución Presupuestaria», tag «Ayuntamiento: "
				+ "Presupuestos», S0.6). Es la única fuente de **gasto ejecutado** del catálogo (S3.1 §7).");
	}

	// --- 1. el censo de instantáneas --------------------------------------------------------------------

	/**
	 * Qué enumera la fuente. A diferencia de OCDS, aquí el censo no son entidades sino <b>fechas</b>: cada una es
	 * una foto entera del presupuesto de gastos. Sin saber cuántas hay y cómo se reparten en el tiempo no se puede
	 * dimensionar nada.
	 */
	@Test
	@Order(1)
	void snapshotCensus() {
		heading(ID, "1. El censo: qué instantáneas hay");

		Response response = api.get(DATES);
		metric(ID, "- " + response.summary());
		assertThat(response.status()).isEqualTo(200);
		SpikeFixtures.save(FIXTURES, "gasto-corriente_fecha.json", response.body());
		SpikeFixtures.saveHeaders(FIXTURES, "gasto-corriente_fecha.headers", response.status(), response.headers());

		JsonNode tree = response.json();
		metric(ID, "- envoltorio: " + tree.propertyNames() + "; `result` es una lista de **URL**, no de objetos.");
		int total = tree.path("totalCount").asInt(-1);
		for (JsonNode url : tree.path("result")) {
			String value = text(url);
			dates.add(value.substring(value.lastIndexOf('/') + 1));
		}
		dates.sort(String::compareTo);
		metric(ID, "- **" + total + "** instantáneas (`totalCount`), " + dates.size() + " URL distintas, de **"
				+ dates.getFirst() + "** a **" + dates.getLast() + "**.");
		assertThat(dates).hasSize(total);
		assertThat(new TreeSet<>(dates)).hasSize(total);

		Response capped = api.get(url(DATES, "rows", "5"));
		metric(ID, "- `rows=5` devuelve `rows`=" + capped.json().path("rows").asInt() + " y "
				+ capped.json().path("result").size() + " URL: **el censo ignora `rows`** y llega entero en una"
				+ " petición (a diferencia del listado de partidas, que topa en " + ROWS + ").");
		assertThat(capped.json().path("result")).hasSize(total);

		var perYear = new TreeMap<String, Integer>();
		for (String date : dates) {
			perYear.merge(date.substring(0, 4), 1, Integer::sum);
		}
		table(ID, List.of("año", "instantáneas", "meses publicados"),
				perYear.entrySet().stream().map(e -> List.of(e.getKey(), String.valueOf(e.getValue()),
						dates.stream().filter(d -> d.startsWith(e.getKey())).map(d -> d.substring(4, 6)).toList()
								.toString()))
						.toList());

		metric(ID, "- La cadencia **no es uniforme**: hay un cierre anual suelto en los primeros años y publicación"
				+ " mensual en los últimos. Los huecos se ven en la tabla y no se rellenan (regla 7).");

		Response head = api.tryHead(DATES);
		metric(ID, "- `HEAD` del censo: " + head.status() + ". `ETag`=" + response.header("ETag") + ", "
				+ "`Last-Modified`=" + response.header("Last-Modified")
				+ " → **sin firma de cambio**, como OCDS (regla 18).");
	}

	// --- 2. forma de una instantánea, paginación y orden ------------------------------------------------

	/**
	 * Envoltorio, campos, tope de página, {@code start}, orden por defecto y {@code sort}. El orden por defecto de
	 * las quejas cambió entre dos días (S2.2) y el de los locales no era {@code id asc} (S2.4): se comprueba.
	 */
	@Test
	@Order(2)
	void shapeAndPaging() {
		heading(ID, "2. Forma de una instantánea, paginación y orden");

		String latest = dates.getLast();
		Response first = api.get(url(EXPENSE + ".json", "rows", "3"));
		metric(ID, "- " + first.summary());
		assertThat(first.status()).isEqualTo(200);
		SpikeFixtures.save(FIXTURES, "gasto-corriente_rows-3.json", first.body());
		SpikeFixtures.saveHeaders(FIXTURES, "gasto-corriente_rows-3.headers", first.status(), first.headers());

		metric(ID, "- envoltorio: " + first.json().propertyNames() + "; campos de la partida: "
				+ results(first).getFirst().propertyNames());
		metric(ID, "- `gasto-corriente.json` **es la instantánea más reciente**: su `fecha` es "
				+ text(results(first).getFirst(), "fecha") + " y la última del censo es " + latest + ".");
		assertThat(text(results(first).getFirst(), "fecha")).isEqualTo(latest);

		Response byDate = api.get(url(snapshotUrl(latest), "rows", "1"));
		metric(ID, "- `fecha/" + latest + ".json` devuelve `totalCount`=" + totalCount(byDate) + " y "
				+ "`gasto-corriente.json` " + totalCount(first) + ": **son el mismo recurso**.");
		assertThat(totalCount(byDate)).isEqualTo(totalCount(first));

		Response capped = api.get(url(snapshotUrl(latest), "rows", "2000"));
		metric(ID, "- `rows=2000` devuelve `rows`=" + capped.json().path("rows").asInt() + " y "
				+ results(capped).size() + " partidas: el tope de la sede son " + ROWS + " (regla 18).");
		assertThat(results(capped)).hasSize(ROWS);

		int total = totalCount(first);
		Response tail = api.get(url(snapshotUrl(latest), "rows", "5", "start", String.valueOf(total - 2), "sort",
				"id asc"));
		metric(ID, "- `start=" + (total - 2) + "` devuelve " + results(tail).size()
				+ " partidas: **`start` sí se aplica** (a diferencia de OCDS, regla 18).");
		assertThat(results(tail)).hasSize(2);

		Response ascending = api.get(url(snapshotUrl(latest), "rows", "3", "sort", "id asc"));
		Response descending = api.get(url(snapshotUrl(latest), "rows", "3", "sort", "id desc"));
		List<String> asc = results(ascending).stream().map(n -> text(n, "id")).toList();
		List<String> desc = results(descending).stream().map(n -> text(n, "id")).toList();
		metric(ID, "- `sort=id asc` → " + asc);
		metric(ID, "- `sort=id desc` → " + desc);
		metric(ID, "- **`sort` se aplica de verdad** (a diferencia de `sort=id desc` en OCDS, S3.1 §3): la primera"
				+ " ascendente y la primera descendente son distintas y cada lista está ordenada.");
		assertThat(asc).isSorted();
		assertThat(desc).isSortedAccordingTo(java.util.Comparator.reverseOrder());

		List<String> byDefault = results(api.get(url(EXPENSE + ".json", "rows", "3", "srsname", "wgs84"))).stream()
				.map(n -> text(n, "id")).toList();
		JsonNode recorded = JSON.readTree(Files.exists(S06_FIXTURE) ? readFixture() : "{}");
		List<String> then = new ArrayList<>();
		recorded.path("result").forEach(n -> then.add(text(n, "id")));
		metric(ID, "- orden por defecto **hoy**: " + byDefault);
		metric(ID, "- orden por defecto **el 2026-09-05** (fixture de S0.6, misma URL): " + then);
		metric(ID, "- " + (byDefault.equals(then) ? "coinciden" : "**no coinciden**")
				+ ": el orden por defecto no está documentado y no se usa para paginar (lección de S2.2 y S2.4)."
				+ " El barrido va siempre con `sort=id asc`.");

		// Lo de S2.2 era que el orden por defecto cambió entre dos días. Aquí cambia entre dos peticiones
		// consecutivas: se pide diez veces lo mismo y se cuentan las respuestas distintas.
		var firstIds = new TreeMap<String, Integer>();
		for (int i = 0; i < 10; i++) {
			List<String> ids = results(api.get(url(EXPENSE + ".json", "rows", "2", "srsname", "wgs84"))).stream()
					.map(n -> text(n, "id")).toList();
			firstIds.merge(ids.isEmpty() ? "(vacío)" : ids.getFirst(), 1, Integer::sum);
			pause();
		}
		counts(ID, "primer id con la misma URL, 10 peticiones seguidas", firstIds);
		metric(ID, "- **El orden por defecto no es estable ni entre dos peticiones seguidas**: "
				+ firstIds.size() + " primeros distintos en 10 peticiones idénticas. Paginar sin `sort` no es que"
				+ " arriesgue un orden distinto mañana (S2.2): es que puede mezclar dos órdenes **dentro del mismo"
				+ " barrido**, perdiendo y repitiendo filas sin que nada lo diga.");
	}

	static String readFixture() {
		try {
			return Files.readString(S06_FIXTURE);
		}
		catch (Exception e) {
			return "{}";
		}
	}

	// --- 3. ¿se reescribe el pasado? --------------------------------------------------------------------

	/**
	 * La pregunta que decide si la ingesta es incremental. El fixture de S0.6 tiene tres partidas completas de la
	 * instantánea 20260831 grabadas el 2026-09-05: si hoy siguen valiendo lo mismo, la instantánea publicada no se
	 * ha tocado en cinco días.
	 */
	@Test
	@Order(3)
	void doesThePastChange() {
		heading(ID, "3. ¿Se reescribe una instantánea ya publicada?");

		JsonNode recorded = JSON.readTree(readFixture());
		var rows = new ArrayList<List<String>>();
		int equal = 0;
		int compared = 0;
		for (JsonNode before : recorded.path("result")) {
			String id = text(before, "id");
			Response now = api.get(url(snapshotUrl(text(before, "fecha")), "rows", "1", "q", "id==" + id));
			List<JsonNode> found = results(now);
			if (found.isEmpty()) {
				rows.add(List.of(id, "no encontrada con `q=id==`", "-", "-"));
				continue;
			}
			JsonNode after = found.getFirst();
			compared++;
			boolean same = AMOUNTS.stream()
					.allMatch(field -> before.path(field).asDouble() == after.path(field).asDouble());
			if (same) {
				equal++;
			}
			rows.add(List.of(id, same ? "idénticos" : "**cambian**", euros(before.path("obligacionNeta").asDouble()),
					euros(after.path("obligacionNeta").asDouble())));
			pause();
		}
		table(ID, List.of("id", "los 8 importes", "obligacionNeta 2026-09-05", "obligacionNeta hoy"), rows);
		metric(ID, "- " + equal + " de " + compared + " partidas comparadas mantienen **los ocho importes** cinco"
				+ " días después. Es la única evidencia disponible en una sesión: una instantánea con fecha de"
				+ " agosto no cambió entre el 5 y el 10 de septiembre.");
		metric(ID, "- Lo que **no** prueba: que la instantánea del mes en curso no se rehaga antes de cerrar el mes."
				+ " Eso solo lo dice una serie, y por eso la ingesta vuelve a leer la última (§8).");
		metric(ID, "- `q=id==<id>` " + (compared > 0 ? "**sí filtra**" : "no filtra")
				+ ": el FIQL de este endpoint acepta `id` (regla 18: lista blanca por endpoint).");
	}

	// --- 4. el barrido completo -------------------------------------------------------------------------

	/**
	 * Las 140 instantáneas enteras, con {@code sort=id asc} y páginas de 500. Se cuenta por identificadores
	 * distintos, no por filas (S2.2 §10). De cada instantánea solo se retienen recuentos y sumas.
	 */
	@Test
	@Order(4)
	void fullSweep() {
		heading(ID, "4. Barrido completo de las " + dates.size() + " instantáneas");

		long started = System.currentTimeMillis();
		for (String date : dates) {
			scanning = date;
			var ids = new TreeSet<String>();
			var concepts = new TreeSet<String>();
			var money = new TreeMap<String, Double>();
			var chapters = new TreeMap<Integer, Integer>();
			var coverage = fieldsByYear.computeIfAbsent(date.substring(0, 4), k -> new TreeMap<>());
			long bytes = 0;
			long millis = 0;
			int total = -1;
			int rows = 0;
			for (int start = 0; total < 0 || start < total; start += ROWS) {
				Response page = api.tryGet(url(snapshotUrl(date), "rows", String.valueOf(ROWS), "start",
						String.valueOf(start), "sort", "id asc"));
				sweepRequests++;
				bytes += page.body().length();
				millis += page.elapsed().toMillis();
				if (page.status() != 200) {
					metric(ID, "- " + date + " start=" + start + " → **" + page.status() + "**");
					break;
				}
				if (total < 0) {
					total = totalCount(page);
				}
				List<JsonNode> results = results(page);
				if (results.isEmpty()) {
					break;
				}
				for (JsonNode row : results) {
					rows++;
					ids.add(text(row, "id"));
					String concept = text(row, "concepto");
					concepts.add(concept);
					for (String field : AMOUNTS) {
						money.merge(field, row.path(field).asDouble(0), Double::sum);
					}
					chapters.merge(row.path("idCapitulo").asInt(-1), 1, Integer::sum);
					for (String path : paths(row)) {
						coverage.merge(path, 1, Integer::sum);
					}
					scanText(row);
					conceptSnapshots.merge(concept, 1, Integer::sum);
					conceptYears.computeIfAbsent(concept, k -> new TreeSet<>()).add(date.substring(0, 4));
					String axis = text(row, "idOrgano") + "|" + text(row, "idPrograma") + "|"
							+ text(row, "idEpigrafe").trim();
					axisYears.computeIfAbsent(axis, k -> new TreeSet<>()).add(date.substring(0, 4));
				}
				pause();
			}
			sweepBytes += bytes;
			sweepMillis += millis;
			sweep.add(new Snapshot(date, total, rows, ids.size(), concepts.size(), money, chapters, bytes, millis));
		}
		long elapsed = System.currentTimeMillis() - started;

		metric(ID, "- " + sweep.size() + " instantáneas, **" + sweepRequests + " peticiones**, "
				+ (sweepBytes / 1024 / 1024) + " MB, " + (sweepMillis / 1000) + " s de espera de red y "
				+ (elapsed / 1000) + " s de reloj (con pausa de " + PAUSE.toMillis() + " ms).");

		var mismatched = sweep.stream().filter(s -> s.distinctIds() != s.totalCount()).toList();
		metric(ID, "- **" + mismatched.size() + " instantáneas** en las que los identificadores distintos no cuadran"
				+ " con `totalCount`. Con `sort=id asc` la paginación por offset no pierde ni repite: el eje de"
				+ " barrido es `id`, que es único dentro de la instantánea.");
		for (Snapshot s : mismatched) {
			metric(ID, "  - " + s.date() + ": totalCount=" + s.totalCount() + ", filas=" + s.rows() + ", ids="
					+ s.distinctIds() + ", conceptos=" + s.distinctConcepts());
		}

		var rows = sweep.stream()
				.map(s -> List.of(s.date(), String.valueOf(s.totalCount()), String.valueOf(s.distinctIds()),
						euros(s.of("creditoDefinitivo")), euros(s.of("gastoComprometido")),
						euros(s.of("obligacionNeta")), euros(s.of("pagoNeto"))))
				.toList();
		table(ID, List.of("fecha", "partidas", "ids", "crédito definitivo", "comprometido", "obligación neta",
				"pago neto"), rows);

		int totalRows = sweep.stream().mapToInt(Snapshot::rows).sum();
		metric(ID, "- Total de filas del histórico: **" + totalRows + "** (una partida por instantánea). Es el"
				+ " tamaño de la tabla si se guardan todas las instantáneas.");
	}

	// --- 5. los ocho importes ---------------------------------------------------------------------------

	/** Qué significan y qué relación guardan. Antes de exponer ninguno como «gasto» (regla 6). */
	@Test
	@Order(5)
	void whatTheAmountsMean() {
		heading(ID, "5. Los ocho importes y su relación");

		Snapshot latest = sweep.getLast();
		table(ID, List.of("importe", "suma en " + latest.date()),
				AMOUNTS.stream().map(a -> List.of(a, euros(latest.of(a)))).toList());

		double identity = latest.of("creditoInicial") + latest.of("creditoModificacion") - latest.of("creditoDefinitivo");
		metric(ID, "- `creditoInicial + creditoModificacion − creditoDefinitivo` = " + euros(identity)
				+ " → el crédito definitivo **es** el inicial más las modificaciones.");
		double remainder = latest.of("creditoDefinitivo") - latest.of("obligacionNeta") - latest.of("remanenteDeCredito");
		metric(ID, "- `creditoDefinitivo − obligacionNeta − remanenteDeCredito` = " + euros(remainder)
				+ " → el remanente es lo no obligado.");
		double pending = latest.of("obligacionNeta") - latest.of("pagoNeto") - latest.of("obligacionPendientePago");
		metric(ID, "- `obligacionNeta − pagoNeto − obligacionPendientePago` = " + euros(pending)
				+ " → lo pendiente de pago es lo obligado y no pagado.");
		metric(ID, "- Lectura: **crédito definitivo** es lo presupuestado, **gasto comprometido** lo dispuesto,"
				+ " **obligación neta** el gasto reconocido (la cifra de «gasto ejecutado») y **pago neto** lo"
				+ " efectivamente pagado. Son cuatro columnas distintas y se publican las cuatro (regla 33).");

		var chapters = new TreeMap<Integer, Integer>(latest.chapters());
		table(ID, List.of("idCapitulo", "partidas en " + latest.date()),
				chapters.entrySet().stream().map(e -> List.of(String.valueOf(e.getKey()), String.valueOf(e.getValue())))
						.toList());
		metric(ID, "- **El nombre del endpoint engaña**: `gasto-corriente` incluye el capítulo 6 (inversiones"
				+ " reales) y los capítulos financieros. Es el presupuesto de gastos entero, no solo el corriente.");
	}

	// --- 6. claves y series -----------------------------------------------------------------------------

	/** Qué identifica a una partida y qué se puede seguir en el tiempo. */
	@Test
	@Order(6)
	void keysAndSeries() {
		heading(ID, "6. Claves: qué se puede seguir en el tiempo");

		Response sample = api.get(url(snapshotUrl(dates.getLast()), "rows", "5", "sort", "id asc"));
		int composed = 0;
		for (JsonNode row : results(sample)) {
			if (text(row, "id").equals(text(row, "fecha") + "-" + text(row, "concepto"))) {
				composed++;
			}
		}
		metric(ID, "- `id` = `fecha` + `-` + `concepto` en " + composed + " de " + results(sample).size()
				+ " partidas: el identificador **lleva la fecha dentro**, así que no identifica a la partida sino"
				+ " a la partida *en esa instantánea*.");

		long inSeveralSnapshots = conceptSnapshots.values().stream().filter(n -> n > 1).count();
		long inSeveralYears = conceptYears.values().stream().filter(y -> y.size() > 1).count();
		metric(ID, "- `concepto` distintos en todo el histórico: **" + conceptSnapshots.size() + "**; aparecen en"
				+ " más de una instantánea " + inSeveralSnapshots + " y en más de un año **" + inSeveralYears + "**.");
		metric(ID, "- Motivo: `concepto` **empieza por los dos dígitos del ejercicio** (`26GUR--1513-6190325`), así"
				+ " que sirve de clave dentro de un año y no cruza años.");

		long axisInSeveralYears = axisYears.values().stream().filter(y -> y.size() > 1).count();
		metric(ID, "- El eje `idOrgano|idPrograma|idEpigrafe` sí cruza: **" + axisInSeveralYears + "** de "
				+ axisYears.size() + " combinaciones aparecen en más de un año. Es el eje de una serie plurianual,"
				+ " y publicarlo como tal exige decidir antes qué se hace con los años sin `programa` (§7).");
	}

	// --- 7. el esquema cambia con los años --------------------------------------------------------------

	/** Cobertura de cada campo por año: los primeros ejercicios no traen la clasificación por programa. */
	@Test
	@Order(7)
	void schemaOverTime() {
		heading(ID, "7. El esquema no es el mismo en todos los años");

		var fields = new TreeSet<String>();
		fieldsByYear.values().forEach(m -> fields.addAll(m.keySet()));
		var years = new ArrayList<>(fieldsByYear.keySet());
		var header = new ArrayList<String>();
		header.add("campo");
		header.addAll(years);
		var rows = new ArrayList<List<String>>();
		for (String field : fields) {
			var row = new ArrayList<String>();
			row.add("`" + field + "`");
			for (String year : years) {
				Map<String, Integer> byField = fieldsByYear.get(year);
				int seen = byField.getOrDefault(field, 0);
				int totalOfYear = byField.getOrDefault("id", 0);
				row.add(seen == 0 ? "—" : (seen == totalOfYear ? "100 %" : String.valueOf(seen)));
			}
			rows.add(row);
		}
		table(ID, header, rows);
		metric(ID, "- Un campo ausente en un año no es un fallo del traductor: **la clasificación por programa no"
				+ " existía** en los primeros ejercicios. Se guarda nulo y se dice en `caveats` (regla 7).");

		Response old = api.get(url(snapshotUrl(dates.getFirst()), "rows", "2", "sort", "id asc"));
		SpikeFixtures.save(FIXTURES, "gasto-corriente_" + dates.getFirst() + "_rows-2.json", old.body());
		Response recent = api.get(url(snapshotUrl(dates.getLast()), "rows", "2", "sort", "id asc"));
		SpikeFixtures.save(FIXTURES, "gasto-corriente_" + dates.getLast() + "_rows-2.json", recent.body());
		metric(ID, "- Fixtures grabados: la instantánea más antigua (" + dates.getFirst() + ") y la más reciente ("
				+ dates.getLast() + "), dos partidas cada una.");

		String padded = text(results(old).getFirst(), "idEpigrafe");
		metric(ID, "- Los códigos antiguos llegan **rellenos con espacios** (`idEpigrafe` de " + padded.length()
				+ " caracteres): el traductor recorta, y eso es normalizar formato, no interpretar (regla 6).");
	}

	// --- 8. datos personales ----------------------------------------------------------------------------

	/** Regla 22: cuatro fuentes, cuatro respuestas. Esta se mide igual que las otras tres. */
	@Test
	@Order(8)
	void personalData() {
		heading(ID, "8. Datos personales (regla 22)");

		var rows = new ArrayList<List<String>>();
		for (String field : TEXT_FIELDS) {
			Map<String, Integer> byPattern = textPatterns.getOrDefault(field, Map.of());
			rows.add(List.of("`" + field + "`", String.valueOf(byPattern.getOrDefault("noVacios", 0)),
					String.valueOf(byPattern.getOrDefault("dniConLetraValida", 0)),
					String.valueOf(byPattern.getOrDefault("NIE", 0)), String.valueOf(byPattern.getOrDefault("CIF", 0)),
					String.valueOf(byPattern.getOrDefault("correo", 0)),
					String.valueOf(byPattern.getOrDefault("telefono", 0)),
					String.valueOf(byPattern.getOrDefault("tratamiento", 0))));
		}
		table(ID, List.of("campo", "no vacíos", "DNI válido", "NIE", "CIF", "correo", "teléfono", "tratamiento"), rows);

		int validDni = textPatterns.values().stream().mapToInt(m -> m.getOrDefault("dniConLetraValida", 0)).sum();
		metric(ID, "- **" + validDni + " DNI con letra de control válida** en las " + sweep.size()
				+ " instantáneas, y cero NIE, cero correos y cero teléfonos.");

		// Lo que sí hay: partidas que nombran a una persona física con la fórmula del presupuesto antiguo.
		int person = textPatterns.values().stream().mapToInt(m -> m.getOrDefault("persona", 0)).sum();
		metric(ID, "- **" + person + " filas** con fórmula de nombre de persona (`viuda de`, `herederos de`) en"
				+ " `partida`. No es texto libre ciudadano: son partidas de pensión heredadas del presupuesto"
				+ " antiguo, y nombran a personas físicas.");
		var rowsByPattern = new ArrayList<List<String>>();
		patternDates.forEach((key, when) -> rowsByPattern.add(List.of("`" + key + "`", String.valueOf(when.size()),
				when.isEmpty() ? "—" : when.first() + " … " + when.last())));
		table(ID, List.of("campo/patrón", "instantáneas con alguna", "de … a"), rowsByPattern);
		metric(ID, "- Ahí está la decisión de la regla 22 de esta fuente, y **no es la de S3.1**: el volumen es"
				+ " ínfimo y la fórmula es fija, pero una fórmula fija fue justo lo que ADR-012 descartó cuando"
				+ " cubría el 47,9 % del texto. Aquí cubre unas pocas filas de un campo que **sí hay que**"
				+ " guardar, porque sin él una partida es un importe sin concepto.");
	}

	// --- 9. los resúmenes: ¿derivados o fuente propia? --------------------------------------------------

	/**
	 * Los tres resúmenes publican series anuales de {@code presupuestado}/{@code gastado}. Si cuadran con las
	 * instantáneas, no se ingieren; si no, la discrepancia se publica.
	 */
	@Test
	@Order(9)
	void summariesAgainstSnapshots() {
		heading(ID, "9. ¿Los resúmenes anuales son derivables de las instantáneas?");

		Response spent = api.get(SPENT_SUMMARY);
		metric(ID, "- " + spent.summary());
		SpikeFixtures.save(FIXTURES, "gastado-resumen.json", spent.body());
		JsonNode tree = spent.json();
		metric(ID, "- forma: **array plano** de `{name, data[{year, presupuestado, gastado}]}` (sin `totalCount`,"
				+ " sin `result`), " + tree.size() + " capítulos.");

		var budgeted = new TreeMap<String, Double>();
		var spentByYear = new TreeMap<String, Double>();
		for (JsonNode chapter : tree) {
			for (JsonNode year : chapter.path("data")) {
				String key = String.valueOf(year.path("year").asInt());
				budgeted.merge(key, year.path("presupuestado").asDouble(0), Double::sum);
				spentByYear.merge(key, year.path("gastado").asDouble(0), Double::sum);
			}
		}

		var lastOfYear = new TreeMap<String, Snapshot>();
		for (Snapshot snapshot : sweep) {
			lastOfYear.put(snapshot.year(), snapshot);
		}

		var rows = new ArrayList<List<String>>();
		for (var entry : spentByYear.entrySet()) {
			Snapshot snapshot = lastOfYear.get(entry.getKey());
			if (snapshot == null) {
				rows.add(List.of(entry.getKey(), "—", euros(budgeted.get(entry.getKey())), euros(entry.getValue()),
						"—", "—"));
				continue;
			}
			double diffBudget = snapshot.of("creditoDefinitivo") - budgeted.get(entry.getKey());
			double diffSpent = snapshot.of("obligacionNeta") - entry.getValue();
			rows.add(List.of(entry.getKey(), snapshot.date(), euros(budgeted.get(entry.getKey())),
					euros(entry.getValue()), euros(diffBudget), euros(diffSpent)));
		}
		table(ID, List.of("año", "última instantánea", "resumen: presupuestado", "resumen: gastado",
				"Δ crédito definitivo", "Δ obligación neta"), rows);
		metric(ID, "- La comparación es contra la **última instantánea de cada año**, que en los años cerrados es la"
				+ " de diciembre y en el año en curso es la del último mes publicado.");

		for (String url : List.of(BODY_SUMMARY, PROGRAM_SUMMARY)) {
			Response response = api.get(url);
			metric(ID, "- " + response.summary());
			SpikeFixtures.save(FIXTURES, url.substring(url.lastIndexOf('/') + 1), response.body());
			metric(ID, "  - " + response.json().size() + " series, misma forma `{name, data[]}`; el eje es "
					+ (url.equals(BODY_SUMMARY) ? "`organo`" : "`programa`") + ".");
		}
	}

	// --- 10. el presupuesto de ingresos -----------------------------------------------------------------

	/** Existe, tiene la misma forma y **no es gasto**: se mide para dejarlo documentado, no para ingerirlo. */
	@Test
	@Order(10)
	void revenueIsAnotherThing() {
		heading(ID, "10. El presupuesto de ingresos (`ingreso-corriente`)");

		Response first = api.get(url(REVENUE + ".json", "rows", "1"));
		metric(ID, "- " + first.summary());
		SpikeFixtures.save(FIXTURES, "ingreso-corriente_rows-1.json", first.body());
		metric(ID, "- `totalCount`=" + totalCount(first) + "; campos: " + results(first).getFirst().propertyNames());

		Response revenueDates = api.get(REVENUE + "/fecha.json");
		metric(ID, "- `ingreso-corriente/fecha.json` publica **" + revenueDates.json().path("totalCount").asInt()
				+ "** instantáneas, con la misma forma que las de gasto.");
		metric(ID, "- **No se ingiere**: el contexto es `spending`, gasto público (ADR-003). Los ingresos son otra"
				+ " cosa y entrarían con decisión propia.");
	}

	// --- 11. coste y forma de la ingesta ----------------------------------------------------------------

	/** Lo que este spike deja decidido sobre cómo se ingiere (ADR-004, ADR-009). */
	@Test
	@Order(11)
	void ingestionCost() {
		heading(ID, "11. Coste y forma de la ingesta");

		int totalRows = sweep.stream().mapToInt(Snapshot::rows).sum();
		metric(ID, "- Histórico completo: **" + sweepRequests + " peticiones**, " + (sweepBytes / 1024 / 1024)
				+ " MB y " + (sweepMillis / 1000) + " s de red para " + totalRows + " filas.");
		metric(ID, "- Una instantánea nueva al mes son " + (sweep.getLast().rows() / ROWS + 1) + " peticiones: el"
				+ " incremental es **barato**, el histórico no cabe en una ejecución de `handle(RawPage)`.");
		metric(ID, "- Forma que se propone (patrón de S3.1): el censo `fecha.json` es una petición y es el"
				+ " `IngestionJob`; cada instantánea que falte la lee después un planificador propio por lotes.");

		var slowest = sweep.stream().max((a, b) -> Long.compare(a.millis(), b.millis())).orElseThrow();
		metric(ID, "- Instantánea más lenta: " + slowest.date() + " (" + slowest.millis() + " ms para "
				+ slowest.rows() + " filas).");
	}

}
