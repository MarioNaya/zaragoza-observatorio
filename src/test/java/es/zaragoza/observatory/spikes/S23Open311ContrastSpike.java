package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.date;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.OPEN311;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
 * S2.3 — Contraste con Open311 (docs/ESTADO.md §4, hilo abierto 4 de {@code citizen}). Informe:
 * docs/spikes/S2.3-contraste-open311.md.
 * <p>
 * S0.3 eligió el listado de sede como fuente de {@code citizen} y dejó Open311 «para contrastar», y S2.2 midió lo
 * que hace incómoda esa elección: el listado publica 89.432 registros desde 2013 mientras las estadísticas
 * municipales cuentan ~40.000 incidencias cerradas al año, sin que se sepa el criterio con el que se publica. La
 * pregunta de este spike es la que quedaba: <b>¿publica Open311 registros que el listado de sede omite?</b> Si los
 * publicara, ingerir Open311 recuperaría parte de lo que falta; si no, el hueco no está entre las dos API.
 * <p>
 * Lo que se mide, por orden: la forma y los topes de Open311; el recorte silencioso de la ventana temporal; el
 * contraste de conjuntos de identificadores en cuatro meses y en un año completo; qué son los registros que faltan;
 * el desfase de las marcas de tiempo entre las dos fuentes; y si Open311 sitúa quejas que la sede no sitúa, que es
 * la única vía por la que podría mejorar la cobertura territorial (29,1 %, S2.2).
 * <p>
 * Open311 devuelve {@code title} y {@code description} sin anonimizar igual que la sede: este spike no imprime ni
 * guarda una sola descripción, los fixtures se graban con {@code saveRedacted} y las comparaciones solo leen
 * identificadores, fechas, estados, categorías y coordenadas (CLAUDE.md regla 22).
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S23Open311ContrastSpike {

	static final String ID = "S2.3-contraste-open311";
	static final String FIXTURES = "open311";

	static final String REQUESTS = OPEN311 + "/requests.json";
	static final String SERVICES = OPEN311 + "/services.json";
	static final String LIST = SEDE + "/quejas-sugerencias/list.json";

	/** Campos de texto escrito por el ciudadano; nunca se imprimen ni se guardan (regla 22). */
	static final Set<String> CITIZEN_TEXT = Set.of("title", "description", "service_notice");

	/** Proyección sin texto libre para el listado de sede (ADR-012) más lo que necesita el contraste. */
	static final String FL_SEDE = "service_request_id,status,service_code,service_name,requested_datetime,"
			+ "updated_datetime,district";

	/** Tope de página: 500 en la sede (S0.5), 1000 en Open311 (medido en la sonda 1). */
	static final int ROWS_SEDE = 500;

	static final int ROWS_311 = 1000;

	/** Nombre de categoría de los servicios que no son quejas ciudadanas (S0.3, S2.2). */
	static final String INTERNAL = "INTERNAL";

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(120));

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
		metric(ID, "Fuentes: " + REQUESTS + " (Open311) y " + LIST + " (sede, fuente de `citizen`).");
	}

	// --- utilidades -------------------------------------------------------------------------------------

	static Map<String, String> params(String... keyValues) {
		var map = new LinkedHashMap<String, String>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	/**
	 * Registros de una respuesta. Open311 devuelve un array cuando hay resultados y un objeto
	 * {@code {totalCount,start,rows}} cuando no hay ninguno (sonda 1): las dos formas se tratan aquí.
	 */
	static List<JsonNode> records(Response response) {
		if (response.failed() || response.status() != 200 || response.body().isBlank()) {
			return List.of();
		}
		JsonNode tree = response.json();
		if (!tree.isArray()) {
			return List.of();
		}
		var out = new ArrayList<JsonNode>();
		tree.forEach(out::add);
		return out;
	}

	static String id(JsonNode record) {
		return text(record, "service_request_id");
	}

	/** Recorre una consulta paginando por {@code start} y devuelve los registros por identificador. */
	static Map<String, JsonNode> sweep(String base, Map<String, String> query, int rows, String label) {
		var byId = new LinkedHashMap<String, JsonNode>();
		int start = 0;
		int pages = 0;
		int seen = 0;
		while (pages < 60) {
			var page = new LinkedHashMap<>(query);
			page.put("rows", String.valueOf(rows));
			page.put("start", String.valueOf(start));
			List<JsonNode> records = records(api.get(ZaragozaSpikeClient.url(base, page)));
			pages++;
			seen += records.size();
			records.forEach(r -> byId.put(id(r), r));
			if (records.size() < rows) {
				break;
			}
			start += rows;
			pause();
		}
		metric(ID, "- " + label + ": " + pages + " páginas, " + seen + " filas, **" + byId.size()
				+ " ids distintos**" + (seen == byId.size() ? "" : " (" + (seen - byId.size()) + " repetidas)"));
		return byId;
	}

	/** Barrido del listado de sede por una ventana FIQL sobre {@code requested_datetime}. */
	static Map<String, JsonNode> sedeWindow(String fromIso, String toIso, String fl, String label) {
		return sweep(LIST, params("fl", fl, "srsname", "wgs84", "sort", "requested_datetime asc", "q",
				"requested_datetime=ge=" + fromIso + ";requested_datetime=lt=" + toIso), ROWS_SEDE, label);
	}

	/**
	 * Barrido de Open311 por una ventana. Ojo: la API recorta toda ventana a 3 meses desde {@code start_date}
	 * (sonda 2), así que quien llame no debe pedir más.
	 */
	static Map<String, JsonNode> open311Window(String fromIso, String toIso, String fl, String label) {
		var query = params("sort", "requested_datetime asc", "start_date", fromIso, "end_date", toIso);
		if (fl != null) {
			query.put("fl", fl);
		}
		return sweep(REQUESTS, query, ROWS_311, label);
	}

	/** Copia de una consulta con un parámetro más (o cambiado). */
	static Map<String, String> with(Map<String, String> query, String key, String value) {
		var copy = new LinkedHashMap<>(query);
		copy.put(key, value);
		return copy;
	}

	/** Fecha de alta del primer registro que devuelve una consulta, o {@code VACÍO}. */
	static String first(Map<String, String> query) {
		List<JsonNode> records = records(api.get(ZaragozaSpikeClient.url(REQUESTS, with(query, "rows", "1"))));
		pause();
		return records.isEmpty() ? "VACÍO" : text(records.get(0), "requested_datetime");
	}

	static Map<String, Integer> countBy(Iterable<JsonNode> records, String field) {
		var out = new TreeMap<String, Integer>();
		records.forEach(r -> out.merge(text(r, field).isBlank() ? "(sin valor)" : text(r, field), 1, Integer::sum));
		return out;
	}

	// --- 1. forma y topes de Open311 --------------------------------------------------------------------

	/**
	 * Antes de comparar nada hay que saber qué devuelve Open311 y hasta dónde. La regla 18 dice «sin tope en
	 * OCDS y Open311»: se comprueba, porque de ahí depende cuántas peticiones cuesta cada ventana.
	 */
	@Test
	@Order(1)
	void shapeAndLimits() {
		heading(ID, "1. Forma, topes y parámetros de Open311");

		Response plain = api.get(ZaragozaSpikeClient.url(REQUESTS, params("rows", "50")));
		metric(ID, "- por defecto: " + plain.summary());
		var fields = new TreeMap<String, Integer>();
		records(plain).forEach(r -> r.propertyNames().forEach(f -> fields.merge(f, 1, Integer::sum)));
		metric(ID, "- campos sin `fl` (" + fields.size() + " sobre 50 registros): " + fields);

		var rows = new ArrayList<List<String>>();
		for (String requested : List.of("500", "1000", "5000")) {
			List<JsonNode> got = records(api.get(ZaragozaSpikeClient.url(REQUESTS,
					params("rows", requested, "fl", "service_request_id"))));
			rows.add(List.of("rows=" + requested, String.valueOf(got.size())));
			pause();
		}
		table(ID, List.of("petición", "registros devueltos"), rows);
		int capped = records(api.get(ZaragozaSpikeClient.url(REQUESTS,
				params("rows", "5000", "fl", "service_request_id")))).size();
		metric(ID, "- **tope de página: " + capped + "** (la regla 18 decía «sin tope»)");
		assertThat(capped).isEqualTo(ROWS_311);

		// La respuesta vacía no es un array: es un objeto con totalCount, que siempre vale 0.
		Response empty = api.get(ZaragozaSpikeClient.url(REQUESTS,
				params("rows", "1", "start_date", "2013-01-01T00:00:00Z", "end_date", "2013-04-01T00:00:00Z")));
		metric(ID, "- ventana sin datos → " + (empty.json().isArray() ? "array" : "objeto") + " `"
				+ empty.body().trim() + "`");
		Response zeroRows = api.get(ZaragozaSpikeClient.url(REQUESTS, params("rows", "0")));
		metric(ID, "- `rows=0` → `" + zeroRows.body().trim()
				+ "`: **`totalCount` vale 0 aunque haya registros**, no sirve para contar");
		assertThat(zeroRows.json().path("totalCount").asInt()).isZero();

		// fl: proyecta, pero pierde una de las dos coordenadas.
		var projected = new TreeMap<String, Integer>();
		records(api.get(ZaragozaSpikeClient.url(REQUESTS,
				params("rows", "50", "fl", "service_request_id,lat,long")))).forEach(
						r -> r.propertyNames().forEach(f -> projected.merge(f, 1, Integer::sum)));
		metric(ID, "- `fl=service_request_id,lat,long` → campos " + projected
				+ " · **`long` desaparece y `lat` no**: la proyección devuelve media coordenada");
		assertThat(projected).containsKey("service_request_id");

		Response services = api.get(SERVICES);
		JsonNode taxonomy = services.json();
		boolean internalDocumented = false;
		for (JsonNode service : taxonomy) {
			if (INTERNAL.equalsIgnoreCase(text(service, "service_name"))
					|| INTERNAL.equalsIgnoreCase(text(service, "service_code"))) {
				internalDocumented = true;
			}
		}
		metric(ID, "- `services.json`: " + taxonomy.size() + " servicios; ¿documenta `INTERNAL`? "
				+ (internalDocumented ? "sí" : "**no**"));
		assertThat(internalDocumented).isFalse();

		SpikeFixtures.saveRedacted(FIXTURES, "open311-requests-page.json", plain.body(), CITIZEN_TEXT);
		SpikeFixtures.saveHeaders(FIXTURES, "open311-requests-page.headers", plain.status(), plain.headers());
	}

	// --- 2. la ventana se recorta en silencio -----------------------------------------------------------

	/**
	 * Sonda decisiva para cualquiera que quiera exportar el histórico de Open311: una ventana de un año responde
	 * 200 con datos y parece completa, pero solo contiene los tres primeros meses. Se mide pidiendo el primer y el
	 * último registro de ventanas cada vez más anchas.
	 */
	@Test
	@Order(2)
	void windowIsSilentlyClamped() {
		heading(ID, "2. La ventana temporal se recorta a 3 meses, sin avisar");

		var rows = new ArrayList<List<String>>();
		record Window(String label, String from, String to) {
		}
		for (Window w : List.of(
				new Window("1 mes", "2025-01-01T00:00:00Z", "2025-02-01T00:00:00Z"),
				new Window("2 meses", "2025-01-01T00:00:00Z", "2025-03-01T00:00:00Z"),
				new Window("3 meses", "2025-01-01T00:00:00Z", "2025-04-01T00:00:00Z"),
				new Window("4 meses", "2025-01-01T00:00:00Z", "2025-05-01T00:00:00Z"),
				new Window("6 meses", "2025-01-01T00:00:00Z", "2025-07-01T00:00:00Z"),
				new Window("1 año", "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))) {
			var query = params("fl", "service_request_id,requested_datetime", "start_date", w.from(), "end_date",
					w.to());
			String oldest = first(with(query, "sort", "requested_datetime asc"));
			String newest = first(with(query, "sort", "requested_datetime desc"));
			rows.add(List.of(w.label(), w.from().substring(0, 10) + " → " + w.to().substring(0, 10), oldest, newest));
		}
		table(ID, List.of("ventana pedida", "fechas", "primer registro", "último registro"), rows);
		metric(ID, "- **A partir de 3 meses el último registro no se mueve**: la API acorta la ventana a"
				+ " `start_date` + 3 meses y responde 200 sin decirlo.");

		String onlyStart = first(params("fl", "service_request_id,requested_datetime", "sort",
				"requested_datetime desc", "start_date", "2025-01-01T00:00:00Z"));
		String onlyEnd = first(params("fl", "service_request_id,requested_datetime", "sort",
				"requested_datetime asc", "end_date", "2025-04-01T00:00:00Z"));
		String noneOldest = first(params("fl", "service_request_id,requested_datetime", "sort",
				"requested_datetime asc"));
		String noneNewest = first(params("fl", "service_request_id,requested_datetime", "sort",
				"requested_datetime desc"));
		metric(ID, "- solo `start_date=2025-01-01` → último registro " + onlyStart + " (3 meses hacia delante)");
		metric(ID, "- solo `end_date=2025-04-01` → primer registro " + onlyEnd + " (3 meses hacia atrás)");
		metric(ID, "- sin fechas → de " + noneOldest + " a " + noneNewest
				+ ": **la respuesta por defecto son los últimos 3 meses**, no el histórico");

		// Trimestres de 2015 a 2017: dónde empieza Open311 de verdad, sin que el recorte engañe.
		var quarters = new ArrayList<List<String>>();
		for (int year = 2015; year <= 2017; year++) {
			for (String[] q : new String[][] { { "01-01", "04-01" }, { "04-01", "07-01" }, { "07-01", "10-01" },
					{ "10-01", "12-31" } }) {
				String oldest = first(params("fl", "service_request_id,requested_datetime", "sort",
						"requested_datetime asc", "start_date", year + "-" + q[0] + "T00:00:00Z", "end_date",
						year + "-" + q[1] + "T00:00:00Z"));
				quarters.add(List.of(year + " " + q[0] + " → " + q[1], oldest));
			}
		}
		table(ID, List.of("trimestre", "primer registro"), quarters);
		metric(ID, "- Open311 empieza en 2017; la sede publica desde 2013-01-08 (S2.2). **Las ventanas anuales de"
				+ " S0.3 no probaban que 2015 y 2016 estuvieran vacíos** (solo miraban su primer trimestre): aquí se"
				+ " comprueban los cuatro de cada año.");

		// Dentro de la ventana de 3 meses, el offset profundo sí funciona.
		var deep = new ArrayList<List<String>>();
		for (String start : List.of("2000", "3000", "4000")) {
			List<JsonNode> got = records(api.get(ZaragozaSpikeClient.url(REQUESTS,
					params("fl", "service_request_id,requested_datetime", "rows", String.valueOf(ROWS_311), "start",
							start, "sort", "requested_datetime asc", "start_date", "2026-01-01T00:00:00Z", "end_date",
							"2026-04-01T00:00:00Z"))));
			deep.add(List.of("start=" + start, String.valueOf(got.size()),
					got.isEmpty() ? "-" : text(got.get(0), "requested_datetime")));
			pause();
		}
		table(ID, List.of("offset", "registros", "primero"), deep);
	}

	// --- 3. contraste mes a mes -------------------------------------------------------------------------

	/**
	 * El contraste, en cuatro meses cerrados repartidos entre 2017 y 2026: ¿hay algún identificador que publique
	 * Open311 y no el listado de sede? Los barridos llevan margen por los lados porque las dos fuentes fechan el
	 * mismo registro con horas distintas (sonda 5).
	 */
	@Test
	@Order(3)
	void monthlyContrast() {
		heading(ID, "3. Contraste mes a mes");

		record Month(String label, String prefix, String wideFrom, String wideTo) {
		}
		var rows = new ArrayList<List<String>>();
		for (Month m : List.of(
				new Month("agosto 2026", "2026-08", "2026-07-30T00:00:00Z", "2026-09-02T00:00:00Z"),
				new Month("enero 2026", "2026-01", "2025-12-30T00:00:00Z", "2026-02-02T00:00:00Z"),
				new Month("noviembre 2025", "2025-11", "2025-10-30T00:00:00Z", "2025-12-02T00:00:00Z"),
				new Month("junio 2017", "2017-06", "2017-05-30T00:00:00Z", "2017-07-02T00:00:00Z"))) {
			Map<String, JsonNode> sede = sedeWindow(m.wideFrom(), m.wideTo(), FL_SEDE, "sede " + m.label());
			Map<String, JsonNode> open311 = open311Window(m.wideFrom(), m.wideTo(),
					"service_request_id,status,service_code,service_name,requested_datetime",
					"open311 " + m.label());

			List<String> month = sede.entrySet().stream()
					.filter(e -> text(e.getValue(), "requested_datetime").startsWith(m.prefix()))
					.map(Map.Entry::getKey)
					.toList();
			List<String> missing = month.stream().filter(k -> !open311.containsKey(k)).toList();
			List<String> missingInternal = missing.stream()
					.filter(k -> INTERNAL.equals(text(sede.get(k), "service_name")))
					.toList();
			List<String> extra = open311.entrySet().stream()
					.filter(e -> text(e.getValue(), "requested_datetime").startsWith(m.prefix()))
					.map(Map.Entry::getKey)
					.filter(k -> !sede.containsKey(k))
					.toList();

			rows.add(List.of(m.label(), String.valueOf(month.size()), String.valueOf(missing.size()),
					String.valueOf(missingInternal.size()), String.valueOf(missing.size() - missingInternal.size()),
					String.valueOf(extra.size())));

			assertThat(extra).as("registros que Open311 publica y la sede no, en " + m.label()).isEmpty();
		}
		table(ID, List.of("mes", "sede", "faltan en Open311", "de ellas INTERNAL", "otras",
				"**en Open311 y no en sede**"), rows);
		metric(ID, "- En los cuatro meses, **cero** registros que Open311 publique y el listado de sede omita, y lo"
				+ " que falta en Open311 son exactamente las `INTERNAL`.");
	}

	// --- 4. contraste de un año completo ----------------------------------------------------------------

	/**
	 * Cuatro meses pueden ser suerte. Se repite sobre 2025 entero (11.895 registros en la sede), enumerando
	 * Open311 por ventanas de 3 meses, que es lo único que la API devuelve completo (sonda 2).
	 */
	@Test
	@Order(4)
	void yearContrast() {
		heading(ID, "4. Contraste de un año completo (2025)");

		Map<String, JsonNode> sede = sedeWindow("2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", FL_SEDE, "sede 2025");

		var open311 = new LinkedHashMap<String, JsonNode>();
		for (String[] window : new String[][] {
				{ "2024-12-31T00:00:00Z", "2025-03-31T00:00:00Z" },
				{ "2025-03-30T00:00:00Z", "2025-06-29T00:00:00Z" },
				{ "2025-06-28T00:00:00Z", "2025-09-27T00:00:00Z" },
				{ "2025-09-26T00:00:00Z", "2025-12-25T00:00:00Z" },
				{ "2025-12-24T00:00:00Z", "2026-01-02T00:00:00Z" } }) {
			open311.putAll(open311Window(window[0], window[1],
					"service_request_id,status,service_code,service_name,requested_datetime",
					"open311 " + window[0].substring(0, 10) + " → " + window[1].substring(0, 10)));
			pause();
		}
		metric(ID, "- Open311 2025 (ventanas unidas): **" + open311.size() + " ids**");

		List<String> year = sede.entrySet().stream()
				.filter(e -> text(e.getValue(), "requested_datetime").startsWith("2025"))
				.map(Map.Entry::getKey)
				.toList();
		Set<String> internal = year.stream()
				.filter(k -> INTERNAL.equals(text(sede.get(k), "service_name")))
				.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
		List<String> missing = year.stream().filter(k -> !open311.containsKey(k)).toList();
		List<String> missingInternal = missing.stream().filter(internal::contains).toList();
		List<String> missingOther = missing.stream().filter(k -> !internal.contains(k)).toList();
		List<String> extra = open311.entrySet().stream()
				.filter(e -> text(e.getValue(), "requested_datetime").startsWith("2025"))
				.map(Map.Entry::getKey)
				.filter(k -> !sede.containsKey(k))
				.toList();

		table(ID, List.of("medida", "registros"), List.of(
				List.of("sede 2025", String.valueOf(year.size())),
				List.of("de ellas INTERNAL", String.valueOf(internal.size())),
				List.of("Open311 (las cinco ventanas unidas)", String.valueOf(open311.size())),
				List.of("de sede, ausentes en Open311", String.valueOf(missing.size())),
				List.of("· INTERNAL", String.valueOf(missingInternal.size())),
				List.of("· otras", String.valueOf(missingOther.size())),
				List.of("**en Open311 y no en sede**", String.valueOf(extra.size()))));

		if (!missingOther.isEmpty()) {
			table(ID, List.of("id", "estado", "categoría", "alta"), missingOther.stream()
					.map(k -> List.of(k, text(sede.get(k), "status"), text(sede.get(k), "service_name"),
							text(sede.get(k), "requested_datetime")))
					.toList());
		}

		assertThat(extra).as("registros que Open311 publica y la sede no, en 2025").isEmpty();
		assertThat(missingInternal).hasSameSizeAs(internal);
	}

	// --- 5. qué son los que faltan ----------------------------------------------------------------------

	/**
	 * Los que faltan se comprueban uno a uno en el detalle de las dos API, que es lo que distingue «Open311 no lo
	 * publica en el listado» de «Open311 no lo tiene».
	 */
	@Test
	@Order(5)
	void whatIsMissing() {
		heading(ID, "5. Qué son los registros que Open311 no publica");

		// Las INTERNAL de un mes cualquiera, tomadas del listado de sede.
		Map<String, JsonNode> june = sedeWindow("2025-06-01T00:00:00Z", "2025-07-01T00:00:00Z", FL_SEDE,
				"sede junio 2025");
		List<JsonNode> internals = june.values().stream()
				.filter(r -> INTERNAL.equals(text(r, "service_name")))
				.toList();
		metric(ID, "- `INTERNAL` en junio de 2025: " + internals.size() + "; códigos de servicio "
				+ countBy(internals, "service_code").keySet());

		var rows = new ArrayList<List<String>>();
		for (JsonNode record : internals.stream().limit(3).toList()) {
			Response detail = api.tryGet(OPEN311 + "/requests/" + id(record) + ".json");
			rows.add(List.of(id(record), INTERNAL, String.valueOf(detail.status())));
			pause();
		}
		// Y los cinco no-INTERNAL que 2025 dejó fuera: se comprueban en las dos API.
		for (String missing : List.of("854868", "855476", "857394", "864284", "866435")) {
			Response open311 = api.tryGet(OPEN311 + "/requests/" + missing + ".json");
			Response sede = api.tryGet(SEDE + "/quejas-sugerencias/" + missing + ".json");
			rows.add(List.of(missing, "no INTERNAL", open311.status() + " (Open311) / " + sede.status() + " (sede)"));
			pause();
		}
		table(ID, List.of("id", "categoría", "detalle"), rows);
		metric(ID, "- Los cinco no-`INTERNAL` de 2025 responden **404 en Open311** (no los tiene) y **400 en el"
				+ " detalle de la sede**, que sí los sirve en el listado: es el mismo defecto de la queja 412792"
				+ " (S2.2).");
	}

	// --- 6. las dos fuentes fechan distinto el mismo registro -------------------------------------------

	/**
	 * Comparar por fecha entre las dos API es una trampa: la misma queja lleva marcas distintas. Se mide el
	 * desfase sobre los identificadores comunes de dos meses, uno de invierno y otro de verano, porque el tamaño
	 * del desfase depende del huso.
	 */
	@Test
	@Order(6)
	void timestampOffset() {
		heading(ID, "6. La misma queja, dos marcas de tiempo distintas");

		record Month(String label, String prefix, String from, String to, String zone) {
		}
		var rows = new ArrayList<List<String>>();
		for (Month m : List.of(
				new Month("agosto 2026", "2026-08", "2026-07-30T00:00:00Z", "2026-09-02T00:00:00Z", "CEST (UTC+2)"),
				new Month("enero 2026", "2026-01", "2025-12-30T00:00:00Z", "2026-02-02T00:00:00Z", "CET (UTC+1)"))) {
			Map<String, JsonNode> sede = sedeWindow(m.from(), m.to(), FL_SEDE, "sede " + m.label());
			Map<String, JsonNode> open311 = open311Window(m.from(), m.to(),
					"service_request_id,requested_datetime", "open311 " + m.label());

			var deltas = new TreeMap<Long, Integer>();
			sede.forEach((key, record) -> {
				JsonNode other = open311.get(key);
				if (other == null || !text(record, "requested_datetime").startsWith(m.prefix())) {
					return;
				}
				LocalDateTime here = date(record.path("requested_datetime"));
				LocalDateTime there = date(other.path("requested_datetime"));
				if (here != null && there != null) {
					deltas.merge(Duration.between(there, here).toMinutes(), 1, Integer::sum);
				}
			});
			String summary = deltas.entrySet().stream()
					.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
					.limit(3)
					.map(e -> (e.getKey() / 60.0) + " h ×" + e.getValue())
					.toList()
					.toString();
			rows.add(List.of(m.label(), m.zone(), summary));
		}
		table(ID, List.of("mes", "huso en Zaragoza", "sede (local) − Open311 (Z)"), rows);
		metric(ID, "- El desfase es **el doble del huso**: la sede publica hora local sin zona y Open311 publica esa"
				+ " misma hora local restándole el huso y marcándola con `Z`, es decir, **convertida dos veces**."
				+ " Leída como UTC, la marca de Open311 cae **1 h antes del instante real en invierno y 2 h antes en"
				+ " verano**.");
	}

	// --- 7. ¿aporta Open311 cobertura territorial? ------------------------------------------------------

	/**
	 * La única razón de producto para ingerir Open311 sería que situara quejas que la sede no sitúa: la cobertura
	 * de punto es el límite del eje territorial (29,1 %, S2.2). Se cruzan las dos fuentes registro a registro.
	 * Open311 no admite {@code fl} para las coordenadas (sonda 1), así que aquí se pide la respuesta completa: trae
	 * texto libre, que no se lee, no se imprime y no se guarda (regla 22).
	 */
	@Test
	@Order(7)
	void territorialCoverage() {
		heading(ID, "7. ¿Sitúa Open311 quejas que la sede no sitúa?");

		Map<String, JsonNode> sede = sedeWindow("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z",
				"service_request_id,status,service_name,requested_datetime,geometry,district", "sede agosto 2026");
		Map<String, JsonNode> open311 = open311Window("2026-07-30T00:00:00Z", "2026-09-02T00:00:00Z", null,
				"open311 agosto 2026 (sin `fl`, por las coordenadas)");

		var cross = new TreeMap<String, Integer>();
		int different = 0;
		int districtSame = 0;
		int districtDifferent = 0;
		for (var entry : sede.entrySet()) {
			JsonNode other = open311.get(entry.getKey());
			if (other == null) {
				continue;
			}
			JsonNode geometry = entry.getValue().path("geometry").path("coordinates");
			boolean herePoint = geometry.isArray() && geometry.size() == 2;
			boolean therePoint = !other.path("lat").isMissingNode() && !other.path("lat").isNull()
					&& !other.path("long").isMissingNode() && !other.path("long").isNull();
			cross.merge("sede " + (herePoint ? "sí" : "no") + " / Open311 " + (therePoint ? "sí" : "no"), 1,
					Integer::sum);
			if (herePoint && therePoint) {
				double dx = Math.abs(geometry.get(0).asDouble() - other.path("long").asDouble());
				double dy = Math.abs(geometry.get(1).asDouble() - other.path("lat").asDouble());
				if (dx > 1e-9 || dy > 1e-9) {
					different++;
				}
			}
			String here = text(entry.getValue(), "district").trim();
			String there = text(other, "district").trim();
			if (!here.isEmpty() && !there.isEmpty()) {
				if (here.equals(there)) {
					districtSame++;
				}
				else {
					districtDifferent++;
				}
			}
		}
		counts(ID, "¿tiene punto?", cross);
		metric(ID, "- coordenadas distintas entre fuentes: **" + different + "**");
		metric(ID, "- junta declarada: " + districtSame + " iguales, **" + districtDifferent + "** distintas");
		int gained = cross.getOrDefault("sede no / Open311 sí", 0);
		metric(ID, "- **quejas que Open311 sitúa y la sede no: " + gained
				+ "** → ingerir Open311 no mejoraría la cobertura territorial");
		assertThat(gained).isZero();
	}

	static void pause() {
		try {
			Thread.sleep(200);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
