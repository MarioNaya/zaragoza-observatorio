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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import es.zaragoza.observatory.spikes.support.SpikeDistricts;
import es.zaragoza.observatory.spikes.support.SpikeFixtures;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S2.4 — Licencias de locales ({@code registro-licencia}): segunda fuente territorial (docs/ESTADO.md §4,
 * camino (a)). Informe: docs/spikes/S2.4-registro-licencia.md.
 * <p>
 * S0.6 la inventarió (dataset 1420, «42.321 locales con geometría, epígrafe y año de licencia») y S2.1 midió su
 * cobertura de punto en el 99,0 % <b>sobre el primer lote de 500</b>. Nada más está comprobado, y las dos
 * lecciones caras de la fase 2 dicen exactamente qué hay que medir antes de escribir el traductor:
 * <ol>
 * <li><b>Qué no pedir</b> (ADR-012). El registro trae texto libre administrativo (`comments` del local y de cada
 * licencia). Si contiene datos de personas físicas, la decisión no es cómo guardarlo sino no descargarlo. Aquí se
 * miden <b>recuentos de patrones</b>, nunca el texto.</li>
 * <li><b>Un barrido se comprueba contando identificadores distintos, no filas</b> (S2.2 §10). Por
 * {@code updated_datetime} las quejas perdían 8.804 registros por empates de fecha; hay que saber por qué eje se
 * puede barrer esta fuente entera sin perder nada.</li>
 * </ol>
 * Y las preguntas propias de la fuente: el modelo real (un local con N licencias), qué significan los códigos
 * ({@code estado}, {@code idIAE}, {@code zonaSaturada}), si hay junta declarada con la que contrastar
 * {@code ST_Contains} (ADR-011 exige guardar las dos), y si el incremental puede apoyarse en {@code lastUpdated}.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S24LicensedPremisesSpike {

	static final String ID = "S2.4-registro-licencia";
	static final String FIXTURES = "urban";

	/** Tope de página de la sede (S0.5, regla 18). */
	static final int ROWS = 500;

	static final String LIST = SEDE + "/registro-licencia.json";
	static final String IAE = SEDE + "/registro-licencia/iae.json";
	static final String ZONA = SEDE + "/registro-licencia/zona-saturada.json";
	static final String PORTAL = SEDE + "/registro-licencia/portal.json";

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(180));

	/** Los 29 polígonos, para medir cobertura territorial (ADR-011). */
	static SpikeDistricts districts;

	/** El barrido completo, en forma compacta (el JSON crudo no cabe en memoria y no hace falta). */
	static final List<Local> sweep = new ArrayList<>();

	/** Recuento de coincidencias de cada patrón personal por campo, del barrido completo (regla 22). */
	static final Map<String, Map<String, Integer>> textPatterns = new TreeMap<>();

	// --- modelo compacto --------------------------------------------------------------------------------

	record Licence(int anyo, long expediente, int orden, int tipoId, String tipoTitle, String resolucion,
			String creationDate, String lastUpdated, String fechaBaja, boolean withComments) {
	}

	record Local(int id, String codPortal, String codVia, String zonaSaturada, String idIAE, int estado,
			String creationDate, String lastUpdated, String fechaBaja, Double lon, Double lat,
			List<Licence> licences) {

		boolean withPoint() {
			return lon != null && lat != null;
		}
	}

	// --- utilidades -------------------------------------------------------------------------------------

	static String url(String base, String... keyValues) {
		var params = new LinkedHashMap<String, String>();
		for (int i = 0; i < keyValues.length; i += 2) {
			params.put(keyValues[i], keyValues[i + 1]);
		}
		return ZaragozaSpikeClient.url(base, params);
	}

	static List<JsonNode> results(Response response) {
		if (response.failed() || response.status() != 200 || response.body().isBlank()) {
			return List.of();
		}
		JsonNode tree = response.json();
		var out = new ArrayList<JsonNode>();
		tree.path("result").forEach(out::add);
		return out;
	}

	static int totalCount(Response response) {
		return response.status() == 200 ? response.json().path("totalCount").asInt(-1) : -1;
	}

	static String nullable(JsonNode node, String field) {
		String value = text(node, field);
		return value.isBlank() ? null : value;
	}

	static Local local(JsonNode node) {
		JsonNode coordinates = node.path("geometry").path("coordinates");
		Double lon = coordinates.size() == 2 ? coordinates.get(0).asDouble() : null;
		Double lat = coordinates.size() == 2 ? coordinates.get(1).asDouble() : null;
		var licences = new ArrayList<Licence>();
		for (JsonNode l : node.path("licencias")) {
			licences.add(new Licence(l.path("id").path("anyo").asInt(-1), l.path("id").path("expediente").asLong(-1),
					l.path("orden").asInt(-1), l.path("tipo").path("id").asInt(-1), text(l.path("tipo"), "title"),
					nullable(l, "resolucion"), nullable(l, "creationDate"), nullable(l, "lastUpdated"),
					nullable(l, "fechaBaja"), !text(l, "comments").isBlank()));
		}
		return new Local(node.path("id").asInt(-1), nullable(node, "codPortal"), nullable(node, "codVia"),
				nullable(node, "zonaSaturada"), nullable(node, "idIAE"), node.path("estado").asInt(-1),
				nullable(node, "creationDate"), nullable(node, "lastUpdated"), nullable(node, "fechaBaja"), lon, lat,
				List.copyOf(licences));
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
		PATTERNS.put("tratamiento", Pattern.compile("(?i)\\b(D\\.|DÑA\\.|DOÑA|SR\\.|SRA\\.|DON)\\s+[A-ZÁÉÍÓÚÑ]"));
		PATTERNS.put("firma", Pattern.compile("(?i)\\b(atentamente|un saludo|fdo\\.?|firmado)\\b"));
	}

	/** Campos de texto libre que se examinan. No se guarda ni se imprime su contenido. */
	static final List<String> TEXT_FIELDS = List.of("emplazamiento", "comments", "actividad", "licencias[].comments");

	static void scanText(JsonNode node) {
		count("emplazamiento", text(node, "emplazamiento"));
		count("comments", text(node, "comments"));
		count("actividad", text(node, "actividad"));
		for (JsonNode l : node.path("licencias")) {
			count("licencias[].comments", text(l, "comments"));
		}
	}

	/** Letras del DNI por resto módulo 23, para saber si una coincidencia de patrón es un DNI de verdad. */
	static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

	static void count(String field, String value) {
		if (value.isBlank()) {
			return;
		}
		Map<String, Integer> byPattern = textPatterns.computeIfAbsent(field, k -> new TreeMap<>());
		byPattern.merge("noVacios", 1, Integer::sum);
		PATTERNS.forEach((name, pattern) -> {
			if (pattern.matcher(value).find()) {
				byPattern.merge(name, 1, Integer::sum);
			}
		});
		// Una coincidencia de patrón no es un DNI: ocho dígitos y una letra los tiene cualquier referencia. La
		// letra de control sí lo distingue, y comprobarla es contar, no leer (regla 22).
		var dni = PATTERNS.get("DNI").matcher(value);
		while (dni.find()) {
			String match = dni.group().replaceAll("[^0-9A-Za-z]", "");
			int number = Integer.parseInt(match.substring(0, 8));
			char letter = Character.toUpperCase(match.charAt(match.length() - 1));
			byPattern.merge(DNI_LETTERS.charAt(number % 23) == letter ? "dniConLetraValida" : "dniConLetraInvalida",
					1, Integer::sum);
		}
	}

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
		metric(ID, "Fuente: " + LIST + " (dataset 1420, S0.6; tag «Urbanismo: Licencias de Locales de la ciudad»).");
		districts = SpikeDistricts.load(api);
		metric(ID, "Juntas cargadas para la resolución geométrica: " + districts.size() + " (ADR-011).");
		assertThat(districts.size()).isEqualTo(29);
	}

	// --- 1. forma, volumen y paginación -----------------------------------------------------------------

	/**
	 * Qué devuelve, cuántos hay, hasta dónde llega {@code rows} y si el orden por defecto sirve para paginar.
	 * S2.2 encontró que el de las quejas no estaba documentado y cambió entre dos días.
	 */
	@Test
	@Order(1)
	void shapeAndVolume() {
		heading(ID, "1. Forma, volumen y paginación");

		Response first = api.get(url(LIST, "rows", "2", "srsname", "wgs84"));
		metric(ID, "- " + first.summary());
		assertThat(first.status()).isEqualTo(200);
		SpikeFixtures.saveRedacted(FIXTURES, "registro-licencia_rows-2.json", first.body(), Set.of("comments"));
		SpikeFixtures.saveHeaders(FIXTURES, "registro-licencia_rows-2.headers", first.status(), first.headers());

		int total = totalCount(first);
		metric(ID, "- `totalCount` = **" + total + "** (S0.6 anotó 42.321 el 2026-09-05).");
		metric(ID, "- envoltorio: " + first.json().propertyNames());
		metric(ID, "- campos del registro: " + results(first).getFirst().propertyNames());

		Response capped = api.get(url(LIST, "rows", "1000", "fl", "id"));
		metric(ID, "- `rows=1000` devuelve `rows`=" + capped.json().path("rows").asInt() + " y "
				+ results(capped).size() + " registros: el tope de la sede son " + ROWS + " (regla 18).");
		assertThat(results(capped)).hasSize(ROWS);

		Response last = api.get(url(LIST, "rows", "1", "start", String.valueOf(total - 1), "sort", "id asc", "fl", "id"));
		metric(ID, "- `start=" + (total - 1) + "&sort=id asc` devuelve id=" + results(last).getFirst().path("id").asInt()
				+ ": `start` sí se aplica (a diferencia de OCDS, regla 18).");
		assertThat(results(last)).hasSize(1);

		List<Integer> defaultOrder = results(api.get(url(LIST, "rows", "10", "fl", "id"))).stream()
				.map(n -> n.path("id").asInt()).toList();
		metric(ID, "- orden por defecto de los 10 primeros ids: " + defaultOrder
				+ " — **no es `id asc`**, no está documentado y no se usa para paginar (lección de S2.2).");
	}

	// --- 2. proyección `fl` -----------------------------------------------------------------------------

	/**
	 * {@code fl} recorta de verdad en la sede (S2.2 lo probó con las quejas), pero aquí hay una trampa que
	 * decide el diseño del traductor: sobre un array anidado, la proyección devuelve el array con los objetos
	 * hijos <b>vacíos</b>.
	 */
	@Test
	@Order(2)
	void projection() {
		heading(ID, "2. Proyección de campos (`fl`) y su trampa con los anidados");

		Response full = api.get(url(LIST, "rows", "5", "srsname", "wgs84"));
		Response trimmed = api.get(url(LIST, "rows", "5", "srsname", "wgs84", "fl", "id,geometry,estado"));
		metric(ID, "- completa: " + full.body().length() + " bytes; recortada a `id,geometry,estado`: "
				+ trimmed.body().length() + " bytes.");
		Set<String> trimmedFields = new LinkedHashSet<>();
		results(trimmed).forEach(n -> n.propertyNames().forEach(trimmedFields::add));
		metric(ID, "- campos devueltos con `fl`: " + trimmedFields);
		assertThat(trimmedFields).containsExactlyInAnyOrder("id", "geometry", "estado");
		assertThat(trimmed.body().length()).isLessThan(full.body().length());

		Response nested = api.get(url(LIST, "rows", "1", "fl", "id,licencias"));
		JsonNode licence = results(nested).getFirst().path("licencias").get(0);
		metric(ID, "- **trampa**: con `fl=id,licencias` cada licencia llega con `id`=" + licence.path("id")
				+ " y `tipo`=" + licence.path("tipo")
				+ ", es decir, **sin expediente, sin año y sin tipo**. La proyección vacía los objetos hijos.");
		assertThat(licence.path("id").size()).isZero();
		assertThat(licence.path("tipo").size()).isZero();
		metric(ID, "- consecuencia para el traductor: o se piden los registros **completos**, o `fl` solo se usa "
				+ "para campos planos. No hay forma de pedir el año de la licencia sin traerse el resto.");

		// El Swagger documenta `removeproperties` («Eliminar propiedades obsoletas»): si funcionase, sería la vía
		// para no descargar el texto libre conservando las licencias, que es lo que ADR-012 pudo hacer con `fl`.
		for (String value : List.of("comments", "true", "comments,actividad")) {
			Response r = api.tryGet(url(LIST, "rows", "1", "srsname", "wgs84", "removeproperties", value));
			boolean stillThere = !results(r).isEmpty() && results(r).getFirst().has("comments");
			metric(ID, "- `removeproperties=" + value + "` → " + r.status() + ", `comments` sigue en la respuesta: "
					+ stillThere + ".");
			assertThat(stillThere).isTrue();
		}
		metric(ID, "- `removeproperties` **se acepta y no hace nada**, el mismo patrón que `q=junta.id==N` (S2.1) y "
				+ "`status=rejected` (S2.2). No hay forma de pedir las licencias sin su texto libre.");
	}

	// --- 3. orden y filtro por fecha --------------------------------------------------------------------

	/** De esto depende que exista incremental: sin filtro por fecha, cada ingesta son 85 páginas. */
	@Test
	@Order(3)
	void sortAndFilter() {
		heading(ID, "3. `sort` y filtro FIQL por fecha");

		var rows = new ArrayList<List<String>>();
		for (String field : List.of("id", "creationDate", "lastUpdated", "estado")) {
			for (String direction : List.of("asc", "desc")) {
				Response r = api.tryGet(url(LIST, "rows", "1", "sort", field + " " + direction, "fl", "id," + field));
				String value = r.status() == 200 && !results(r).isEmpty()
						? text(results(r).getFirst(), field.equals("id") ? "id" : field) : "-";
				rows.add(List.of("`sort=" + field + " " + direction + "`", String.valueOf(r.status()), value));
			}
		}
		table(ID, List.of("petición", "estado", "primer valor"), rows);

		Response wrong = api.tryGet(url(LIST, "rows", "1", "q", "lastUpdated>=2026-09-01T00:00:00"));
		metric(ID, "- `q=lastUpdated>=…` responde **" + wrong.status()
				+ "** (`Not a comparison expression`): el operador de FIQL es `=ge=`, no `>=`.");

		var fiql = new ArrayList<List<String>>();
		for (String expression : List.of("lastUpdated=ge=2026-09-01T00:00:00", "lastUpdated=ge=2026-09-01",
				"creationDate=ge=2026-01-01T00:00:00", "estado==1", "estado==3", "zonaSaturada==C")) {
			Response r = api.tryGet(url(LIST, "rows", "1", "q", expression, "fl", "id"));
			fiql.add(List.of("`q=" + expression + "`", String.valueOf(r.status()), String.valueOf(totalCount(r))));
		}
		table(ID, List.of("filtro", "estado", "totalCount"), fiql);

		Response window = api.get(url(LIST, "rows", "1", "q", "lastUpdated=ge=2026-09-01T00:00:00", "fl", "id"));
		metric(ID, "- El filtro **sí recorta**: " + totalCount(window) + " registros modificados desde el 1 de "
				+ "septiembre frente a los " + totalCount(api.get(url(LIST, "rows", "1", "fl", "id")))
				+ " del total. Comprobado además que el mínimo `lastUpdated` de la ventana cae dentro de ella.");
		Response inside = api.get(url(LIST, "rows", "1", "q", "lastUpdated=ge=2026-09-01T00:00:00", "sort",
				"lastUpdated asc", "fl", "id,lastUpdated"));
		String min = text(results(inside).getFirst(), "lastUpdated");
		metric(ID, "- mínimo de la ventana: `" + min + "`.");
		assertThat(min).isGreaterThanOrEqualTo("2026-09-01");
	}

	// --- 4. barrido completo ----------------------------------------------------------------------------

	/**
	 * El barrido de verdad, contando <b>identificadores distintos</b> (S2.2 §10). Se hace por {@code id asc},
	 * que es el único eje sin empates posibles, y se guarda en {@link #sweep} para los apartados siguientes.
	 */
	@Test
	@Order(4)
	void fullSweep() {
		heading(ID, "4. Barrido completo por `id asc`");

		int total = totalCount(api.get(url(LIST, "rows", "1", "fl", "id")));
		var ids = new LinkedHashSet<Integer>();
		int pages = 0;
		int rows = 0;
		long bytes = 0;
		long startedAt = System.nanoTime();
		for (int start = 0; start < total; start += ROWS) {
			Response page = api.get(url(LIST, "rows", String.valueOf(ROWS), "start", String.valueOf(start), "sort",
					"id asc", "srsname", "wgs84"));
			assertThat(page.status()).isEqualTo(200);
			pages++;
			bytes += page.body().length();
			for (JsonNode node : results(page)) {
				rows++;
				Local premises = local(node);
				if (ids.add(premises.id())) {
					sweep.add(premises);
				}
				scanText(node);
			}
			if (start == 0) {
				SpikeFixtures.saveRedacted(FIXTURES, "registro-licencia_page0_rows-500.json", page.body(),
						Set.of("comments"));
			}
		}
		long seconds = Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();
		metric(ID, "- páginas=" + pages + " filas=" + rows + " **ids distintos=" + ids.size() + "** totalCount="
				+ total + " bytes=" + bytes + " (" + bytes / 1024 / 1024 + " MB) en " + seconds + " s.");
		metric(ID, "- filas − ids distintos = " + (rows - ids.size())
				+ ". Por `id asc` el barrido es exacto: no hay empates posibles y ninguna fila se repite.");
		assertThat(ids).hasSize(rows);
		assertThat(sweep).hasSizeGreaterThan(40_000);
	}

	/**
	 * ¿Y por el eje del incremental? Si {@code lastUpdated} tiene empates masivos (cargas por lotes), paginar
	 * por él pierde registros igual que en las quejas. Se compara el conjunto de ids con el del barrido bueno.
	 */
	@Test
	@Order(5)
	void sweepByLastUpdated() {
		heading(ID, "5. El mismo barrido por `lastUpdated asc` (el eje del incremental)");

		int total = totalCount(api.get(url(LIST, "rows", "1", "fl", "id")));
		var ids = new LinkedHashSet<Integer>();
		int rows = 0;
		for (int start = 0; start < total; start += ROWS) {
			Response page = api.get(url(LIST, "rows", String.valueOf(ROWS), "start", String.valueOf(start), "sort",
					"lastUpdated asc", "fl", "id,lastUpdated"));
			assertThat(page.status()).isEqualTo(200);
			for (JsonNode node : results(page)) {
				rows++;
				ids.add(node.path("id").asInt());
			}
		}
		metric(ID, "- filas=" + rows + " **ids distintos=" + ids.size() + "** (por `id asc` fueron " + sweep.size()
				+ ").");
		metric(ID, "- pérdida por empates de fecha: **" + (rows - ids.size()) + "** filas repetidas, es decir "
				+ (sweep.size() - ids.size()) + " registros que un barrido por este eje **no vería**.");

		Map<String, Integer> ties = new TreeMap<>();
		sweep.forEach(l -> ties.merge(l.lastUpdated() == null ? "(sin fecha)" : l.lastUpdated(), 1, Integer::sum));
		long tied = ties.values().stream().filter(n -> n > 1).mapToInt(Integer::intValue).sum();
		int maxTie = ties.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		metric(ID, "- en el barrido bueno hay " + ties.size() + " valores distintos de `lastUpdated` para "
				+ sweep.size() + " registros; **" + tied + "** comparten fecha con otro y el empate mayor es de "
				+ maxTie + " registros.");
	}

	// --- 6. cobertura territorial -----------------------------------------------------------------------

	/** S2.1 midió el 99,0 % sobre los primeros 500. Aquí, sobre los 42.000 y pico. */
	@Test
	@Order(6)
	void territorialCoverage() {
		heading(ID, "6. Cobertura territorial sobre el registro entero");
		assertThat(sweep).isNotEmpty();

		int withPoint = 0, resolved = 0, outside = 0, ambiguous = 0;
		Map<String, Integer> byDistrict = new TreeMap<>();
		for (Local premises : sweep) {
			if (!premises.withPoint()) {
				continue;
			}
			withPoint++;
			List<Integer> hits = districts.locate(premises.lon(), premises.lat());
			if (hits.isEmpty()) {
				outside++;
			}
			else {
				resolved++;
				if (hits.size() > 1) {
					ambiguous++;
				}
				byDistrict.merge(hits.getFirst() + " " + districts.title(hits.getFirst()), 1, Integer::sum);
			}
		}
		metric(ID, "- registros=" + sweep.size() + " con punto=" + withPoint + " ("
				+ String.format("%.1f", 100.0 * withPoint / sweep.size()) + " %) resueltos a junta=" + resolved
				+ " fuera de las 29 juntas=" + outside + " ambiguos (solape)=" + ambiguous + ".");
		metric(ID, "- sin punto no hay junta (ADR-011): " + (sweep.size() - withPoint)
				+ " locales quedarían `unassigned`, y se cuentan como tales (regla 7).");
		counts(ID, "junta", byDistrict);
	}

	/**
	 * ¿Hay junta declarada con la que contrastar? ADR-011 exige guardar {@code districtDeclared} «lo que diga el
	 * origen». Si el origen no dice nada, hay que saberlo antes de diseñar la tabla.
	 */
	@Test
	@Order(7)
	void declaredDistrict() {
		heading(ID, "7. ¿Declara junta el origen?");

		Response portal = api.get(url(PORTAL, "rows", "2", "srsname", "wgs84"));
		metric(ID, "- " + portal.summary());
		SpikeFixtures.save(FIXTURES, "registro-licencia-portal_rows-2.json", portal.body());
		Set<String> fields = new LinkedHashSet<>();
		results(portal).forEach(n -> n.propertyNames().forEach(fields::add));
		metric(ID, "- campos de `registro-licencia/portal`: " + fields + " — **sin `junta`**, al contrario que "
				+ "`locales-vacios.portal`, que sí la trae con `id` y `title` (S2.1).");
		assertThat(fields).doesNotContain("junta");

		Set<String> premisesFields = new LinkedHashSet<>();
		results(api.get(url(LIST, "rows", "5", "srsname", "wgs84"))).forEach(n -> n.propertyNames()
				.forEach(premisesFields::add));
		metric(ID, "- campos del local: " + premisesFields + " — tampoco hay junta, barrio ni distrito.");
		assertThat(premisesFields).doesNotContain("junta", "district", "barrio");
		metric(ID, "- conclusión: en esta fuente `districtDeclared` **no existe**. Se guarda a `null` y se dice, "
				+ "en vez de rellenarlo cruzando `codPortal` contra otro recurso (ADR-011: nada de resolver por "
				+ "dirección, y S2.1 midió que el callejero no distingue el acierto del fallo).");
	}

	// --- 8. el modelo real ------------------------------------------------------------------------------

	/** Un local con N licencias: cardinalidad, tipos, años y códigos. Es la forma de la tabla. */
	@Test
	@Order(8)
	void model() {
		heading(ID, "8. El modelo real: un local con N licencias");
		assertThat(sweep).isNotEmpty();

		long licences = sweep.stream().mapToLong(l -> l.licences().size()).sum();
		long without = sweep.stream().filter(l -> l.licences().isEmpty()).count();
		int max = sweep.stream().mapToInt(l -> l.licences().size()).max().orElse(0);
		metric(ID, "- locales=" + sweep.size() + " licencias=" + licences + " (media "
				+ String.format("%.2f", (double) licences / sweep.size()) + ", máximo " + max + " en un local, "
				+ without + " locales sin ninguna).");

		// Qué combinación identifica una licencia dentro de su local: es la clave primaria de la tabla, y una
		// clave que no sea única convierte el upsert idempotente (regla 5) en pérdida silenciosa de filas.
		var candidates = new LinkedHashMap<String, java.util.function.Function<Licence, String>>();
		candidates.put("(anyo, expediente)", x -> x.anyo() + "/" + x.expediente());
		candidates.put("(anyo, expediente, tipo)", x -> x.anyo() + "/" + x.expediente() + "/" + x.tipoId());
		candidates.put("(orden)", x -> String.valueOf(x.orden()));
		var keyRows = new ArrayList<List<String>>();
		candidates.forEach((name, key) -> {
			long collisions = 0;
			int localesAffected = 0;
			for (Local premises : sweep) {
				var seen = new LinkedHashSet<String>();
				long repeated = premises.licences().stream().filter(x -> !seen.add(key.apply(x))).count();
				collisions += repeated;
				if (repeated > 0) {
					localesAffected++;
				}
			}
			keyRows.add(List.of(name, String.valueOf(collisions), String.valueOf(localesAffected)));
		});
		table(ID, List.of("clave candidata dentro del local", "licencias que colisionan", "locales afectados"),
				keyRows);

		Map<String, Integer> estados = new TreeMap<>();
		sweep.forEach(l -> estados.merge(String.valueOf(l.estado()), 1, Integer::sum));
		counts(ID, "`estado`", estados);
		metric(ID, "- `estado` es un **código sin taxonomía publicada**: no hay endpoint que lo describa en el tag "
				+ "del Swagger (S1.2) y el catálogo no lo documenta. Se guarda el código crudo (regla 6: no se "
				+ "inventa la etiqueta).");

		Map<String, Integer> tipos = new TreeMap<>();
		sweep.forEach(l -> l.licences().forEach(x -> tipos.merge(x.tipoId() + " " + x.tipoTitle(), 1, Integer::sum)));
		counts(ID, "tipo de licencia", tipos);

		Map<String, Integer> years = new TreeMap<>();
		sweep.forEach(l -> l.licences().forEach(x -> years.merge(String.valueOf(x.anyo()), 1, Integer::sum)));
		table(ID, List.of("año de licencia", "n"), years.entrySet().stream()
				.map(e -> List.of(e.getKey(), String.valueOf(e.getValue()))).toList());

		long withBaja = sweep.stream().filter(l -> l.fechaBaja() != null).count();
		long licenceBaja = sweep.stream().flatMap(l -> l.licences().stream()).filter(x -> x.fechaBaja() != null)
				.count();
		metric(ID, "- `fechaBaja` está documentada en el Swagger para el local y para la licencia; en el registro "
				+ "entero aparece en " + withBaja + " locales y " + licenceBaja + " licencias.");

		long withZone = sweep.stream().filter(l -> l.zonaSaturada() != null).count();
		long withIae = sweep.stream().filter(l -> l.idIAE() != null).count();
		long withPortal = sweep.stream().filter(l -> l.codPortal() != null).count();
		metric(ID, "- `zonaSaturada` en " + withZone + " locales, `idIAE` en " + withIae + ", `codPortal` en "
				+ withPortal + ".");

		Map<String, Integer> resolutionYear = new TreeMap<>();
		sweep.forEach(l -> l.licences().forEach(x -> resolutionYear.merge(
				x.resolucion() == null ? "(sin resolución)" : x.resolucion().substring(0, 4), 1, Integer::sum)));
		metric(ID, "- licencias sin fecha de `resolucion`: " + resolutionYear.getOrDefault("(sin resolución)", 0)
				+ " de " + licences + ".");
	}

	// --- 9. texto libre y datos personales --------------------------------------------------------------

	/**
	 * La pregunta de ADR-012 aplicada a esta fuente. Se cuentan coincidencias de patrón sobre el barrido
	 * completo; ni una línea de texto sale por aquí (regla 22).
	 */
	@Test
	@Order(9)
	void freeText() {
		heading(ID, "9. Texto libre: cuánto hay y qué contiene");
		assertThat(textPatterns).isNotEmpty();

		var rows = new ArrayList<List<String>>();
		for (String field : TEXT_FIELDS) {
			Map<String, Integer> byPattern = textPatterns.getOrDefault(field, Map.of());
			var row = new ArrayList<String>();
			row.add("`" + field + "`");
			row.add(String.valueOf(byPattern.getOrDefault("noVacios", 0)));
			PATTERNS.keySet().forEach(name -> row.add(String.valueOf(byPattern.getOrDefault(name, 0))));
			rows.add(List.copyOf(row));
		}
		var header = new ArrayList<String>();
		header.add("campo");
		header.add("no vacíos");
		header.addAll(PATTERNS.keySet());
		table(ID, header, rows);
		metric(ID, "- Los recuentos son de **patrón**, no de dato personal confirmado: un `CIF` cuenta también "
				+ "matrículas y referencias, y `tratamiento` cuenta cualquier «D.» seguido de mayúscula (en "
				+ "`emplazamiento` son nombres de calle: «D. JAIME I»). Sirven para decidir si hace falta ADR "
				+ "sobre el texto, no para etiquetar registros (regla 6).");

		var dni = new ArrayList<List<String>>();
		for (String field : TEXT_FIELDS) {
			Map<String, Integer> byPattern = textPatterns.getOrDefault(field, Map.of());
			dni.add(List.of("`" + field + "`", String.valueOf(byPattern.getOrDefault("dniConLetraValida", 0)),
					String.valueOf(byPattern.getOrDefault("dniConLetraInvalida", 0))));
		}
		table(ID, List.of("campo", "DNI con letra de control válida", "coincidencias que no lo son"), dni);
		metric(ID, "- La letra de control separa el DNI de verdad de la referencia con forma de DNI. Es un "
				+ "recuento, no una lectura: el spike no imprime ni guarda una sola cadena (regla 22).");
	}

	// --- 10. taxonomías del tag -------------------------------------------------------------------------

	/** Los códigos del local apuntan a dos catálogos propios del tag. Sin ellos, `idIAE` es un número. */
	@Test
	@Order(10)
	void taxonomies() {
		heading(ID, "10. Taxonomías del tag: `iae` y `zona-saturada`");

		Response iae = api.get(url(IAE, "rows", String.valueOf(ROWS)));
		metric(ID, "- " + iae.summary());
		metric(ID, "- `iae`: totalCount=" + totalCount(iae) + ", primera página=" + results(iae).size()
				+ ", campos=" + results(iae).getFirst().propertyNames() + ".");
		SpikeFixtures.save(FIXTURES, "registro-licencia-iae_page0.json", iae.body());

		Response zona = api.get(url(ZONA, "rows", "100", "srsname", "wgs84"));
		metric(ID, "- `zona-saturada`: totalCount=" + totalCount(zona) + ", campos="
				+ results(zona).getFirst().propertyNames() + " (trae polígono).");
		SpikeFixtures.save(FIXTURES, "registro-licencia-zona-saturada.json", zona.body());

		Set<String> zones = new LinkedHashSet<>();
		results(zona).forEach(n -> zones.add(text(n, "zona") + " " + text(n, "title")));
		metric(ID, "- zonas saturadas publicadas: " + zones);

		Set<String> used = new java.util.TreeSet<>();
		sweep.forEach(l -> {
			if (l.zonaSaturada() != null) {
				used.add(l.zonaSaturada());
			}
		});
		metric(ID, "- códigos de `zonaSaturada` que usan los locales: " + used);
	}

	// --- 11. frescura -----------------------------------------------------------------------------------

	/** Qué se puede observar de esta fuente con el eje de ADR-005 y qué cabeceras da. */
	@Test
	@Order(11)
	void freshness() {
		heading(ID, "11. Frescura: cabeceras y eje observable");

		Response list = api.get(url(LIST, "rows", "1", "fl", "id"));
		metric(ID, "- listado: `Last-Modified`=" + list.header("Last-Modified") + " `ETag`=" + list.header("ETag")
				+ " `Cache-Control`=" + list.header("Cache-Control") + ".");

		Response conditional = api.tryGet(url(LIST, "rows", "1", "fl", "id"),
				h -> h.set("If-Modified-Since", "Wed, 09 Sep 2026 00:00:00 GMT"));
		metric(ID, "- con `If-Modified-Since`: **" + conditional.status()
				+ "** (regla 18: la sede nunca lo honra).");

		Response head = api.tryHead(url(LIST, "rows", "1", "fl", "id"));
		metric(ID, "- `HEAD` sobre el listado: " + head.status() + ".");

		Response newest = api.get(url(LIST, "rows", "1", "sort", "lastUpdated desc", "fl", "id,lastUpdated"));
		Response oldest = api.get(url(LIST, "rows", "1", "sort", "lastUpdated asc", "fl", "id,lastUpdated"));
		metric(ID, "- `API_MAX_DATE` por `lastUpdated`: máximo `" + text(results(newest).getFirst(), "lastUpdated")
				+ "`, mínimo `" + text(results(oldest).getFirst(), "lastUpdated") + "`.");

		// El listado sí trae Last-Modified, pero la cabecera cambia con `sort`: describe la página, no el recurso.
		metric(ID, "- **la cabecera describe la página, no el recurso**: con `sort=lastUpdated desc` vale `"
				+ newest.header("Last-Modified") + "` y con `sort=lastUpdated asc` vale `"
				+ oldest.header("Last-Modified") + "`, que son exactamente el `lastUpdated` del registro devuelto "
				+ "en cada caso. Observar esta ficha por `FILE_HEADERS` mediría el orden de la petición.");
		assertThat(newest.header("Last-Modified")).isNotEqualTo(oldest.header("Last-Modified"));
		metric(ID, "- `lastUpdated` ya está en la lista blanca de `ObservationUrls.DATE_FIELDS` (ADR-005), así que "
				+ "el monitor de frescura observa esta ficha por ese eje sin tocar nada.");
	}

}
