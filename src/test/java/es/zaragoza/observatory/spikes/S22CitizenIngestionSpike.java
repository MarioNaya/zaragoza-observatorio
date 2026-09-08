package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

import es.zaragoza.observatory.spikes.support.SpikeFixtures;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S2.2 — Ingesta de quejas y sugerencias: paginación, incremental y texto libre (docs/ESTADO.md §4, fase 2
 * paso 3). Informe: docs/spikes/S2.2-quejas-ingesta.md.
 * <p>
 * S0.3 eligió la fuente ({@code sede/servicio/quejas-sugerencias/list.json}) y dejó por escrito cuatro cosas sin
 * comprobar que deciden el diseño de la ingesta y una ADR (SPEC.md §9):
 * <ol>
 * <li>¿Funciona {@code fl}? Si se puede <b>no pedir</b> {@code title}/{@code description}, el texto libre con
 * datos personales no llega a entrar nunca, y la decisión sobre él deja de ser una política de borrado para ser
 * una de no recogida (regla 22, SPEC.md §9).</li>
 * <li>¿Cuántos registros tiene el listado de verdad? S0.3 acotó «entre 50.000 y 100.000» y comparó con las
 * ~40.000 cerradas/año de {@code statistics}: sin el número no se puede hablar de volúmenes por territorio.</li>
 * <li>¿Admite FIQL y {@code sort} sobre {@code updated_datetime}? De eso depende que la ingesta incremental
 * capture los <b>cierres</b>, que es la mitad del producto (tiempo de respuesta).</li>
 * <li>¿Cuánta cobertura territorial hay en el histórico, no solo en los 500 recientes? Y qué variantes de nombre
 * de junta aparecen, que es la evidencia que ADR-011 §5 exige antes de escribir un sinónimo.</li>
 * </ol>
 * Sobre el texto libre se miden <b>recuentos de patrones</b> (DNI/NIE, correo, teléfono, matrícula, firmas), nunca
 * el texto: este spike no imprime ni guarda una sola descripción (regla 22). Los fixtures se graban con
 * {@code saveRedacted}.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S22CitizenIngestionSpike {

	static final String ID = "S2.2-quejas-ingesta";
	static final String FIXTURES = "open311";

	/** Tope de página de la sede (S0.5, regla 18). */
	static final int ROWS = 500;

	static final String LIST = SEDE + "/quejas-sugerencias/list.json";

	/** Campos de texto escrito por el ciudadano; nunca se imprimen ni se guardan (regla 22). */
	static final Set<String> CITIZEN_TEXT = Set.of("title", "description", "service_notice");

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(120));

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
		metric(ID, "Fuente: " + LIST + " (S0.3 recomendación 1).");
	}

	// --- utilidades -------------------------------------------------------------------------------------

	static String listUrl(Map<String, String> params) {
		return ZaragozaSpikeClient.url(LIST, params);
	}

	static Map<String, String> params(String... keyValues) {
		var map = new LinkedHashMap<String, String>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	/** Registros de una respuesta que se espera array; vacío si no lo es (400, cuerpo vacío…). */
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

	// --- 1. ¿funciona fl? -------------------------------------------------------------------------------

	/**
	 * Si {@code fl} recorta la respuesta, la ingesta puede no pedir el texto libre. Se compara la respuesta
	 * completa con la recortada sobre los mismos 5 registros.
	 */
	@Test
	@Order(1)
	void flProjection() {
		heading(ID, "1. Proyección de campos (`fl`)");

		Response full = api.get(listUrl(params("rows", "5", "srsname", "wgs84")));
		metric(ID, "- completa: " + full.summary());
		List<JsonNode> fullRecords = records(full);
		assertThat(fullRecords).hasSize(5);

		var fullFields = new TreeMap<String, Integer>();
		fullRecords.forEach(r -> r.propertyNames().forEach(f -> fullFields.merge(f, 1, Integer::sum)));
		metric(ID, "- campos devueltos sin `fl` (" + fullFields.size() + "): " + String.join(", ", fullFields.keySet()));

		String projection = "service_request_id,status,service_code,service_name,requested_datetime,"
				+ "updated_datetime,geometry,address_string,district";
		Response projected = api.get(listUrl(params("rows", "5", "srsname", "wgs84", "fl", projection)));
		metric(ID, "- con `fl=" + projection + "`: " + projected.summary());
		List<JsonNode> projectedRecords = records(projected);

		var projectedFields = new TreeMap<String, Integer>();
		projectedRecords.forEach(r -> r.propertyNames().forEach(f -> projectedFields.merge(f, 1, Integer::sum)));
		metric(ID, "- campos devueltos con `fl` (" + projectedFields.size() + "): "
				+ String.join(", ", projectedFields.keySet()));

		boolean textDropped = CITIZEN_TEXT.stream().noneMatch(projectedFields::containsKey);
		boolean textPresent = CITIZEN_TEXT.stream().anyMatch(fullFields::containsKey);
		metric(ID, "- **¿`fl` recorta?** " + (projectedFields.size() < fullFields.size() ? "sí" : "no")
				+ " · ¿desaparece el texto libre? " + (textDropped ? "sí" : "no")
				+ " (el texto libre está en la respuesta completa: " + (textPresent ? "sí" : "no") + ")");
		metric(ID, "- bytes: sin `fl` " + full.body().length() + ", con `fl` " + projected.body().length());

		// Un solo campo, para ver si la proyección es real o la API ignora el parámetro.
		Response single = api.get(listUrl(params("rows", "5", "fl", "service_request_id")));
		var singleFields = new TreeMap<String, Integer>();
		records(single).forEach(r -> r.propertyNames().forEach(f -> singleFields.merge(f, 1, Integer::sum)));
		metric(ID, "- con `fl=service_request_id`: " + singleFields.size() + " campos ("
				+ String.join(", ", singleFields.keySet()) + "), " + single.body().length() + " bytes");

		assertThat(fullRecords).isNotEmpty();
	}

	// --- 2. tamaño real del listado ---------------------------------------------------------------------

	/**
	 * El listado es un array sin {@code totalCount} (S0.3), así que el tamaño solo se puede acotar sondeando
	 * {@code start}. Búsqueda binaria con {@code rows=1}: ~17 peticiones diminutas.
	 */
	@Test
	@Order(2)
	void totalRecords() {
		heading(ID, "2. Tamaño real del listado");

		Response first = api.get(listUrl(params("rows", "1", "start", "0")));
		List<JsonNode> firstRecords = records(first);
		metric(ID, "- `start=0`: " + first.summary());
		if (!firstRecords.isEmpty()) {
			metric(ID, "- primer registro: id=" + text(firstRecords.get(0), "service_request_id") + " requested="
					+ text(firstRecords.get(0), "requested_datetime") + " (orden por defecto)");
		}

		int low = 0;          // hay registro en start=low
		int high = 1_000_000; // no hay registro en start=high
		int probes = 0;
		while (high - low > 1) {
			int mid = low + (high - low) / 2;
			boolean present = !records(api.get(listUrl(params("rows", "1", "start", String.valueOf(mid))))).isEmpty();
			probes++;
			if (present) {
				low = mid;
			}
			else {
				high = mid;
			}
		}
		metric(ID, "- **último `start` con registro: " + low + "** → total = " + (low + 1) + " registros ("
				+ probes + " sondeos)");

		Response last = api.get(listUrl(params("rows", "1", "start", String.valueOf(low))));
		List<JsonNode> lastRecords = records(last);
		if (!lastRecords.isEmpty()) {
			metric(ID, "- último registro: id=" + text(lastRecords.get(0), "service_request_id") + " requested="
					+ text(lastRecords.get(0), "requested_datetime"));
		}

		// ¿Es estable el offset profundo? Dos lecturas seguidas del mismo start deben dar el mismo id.
		Response repeat = api.get(listUrl(params("rows", "1", "start", String.valueOf(low / 2))));
		Response repeatAgain = api.get(listUrl(params("rows", "1", "start", String.valueOf(low / 2))));
		String a = records(repeat).isEmpty() ? "-" : text(records(repeat).get(0), "service_request_id");
		String b = records(repeatAgain).isEmpty() ? "-" : text(records(repeatAgain).get(0), "service_request_id");
		metric(ID, "- offset profundo estable (`start=" + (low / 2) + "` dos veces): " + a + " / " + b + " → "
				+ (a.equals(b) ? "sí" : "**no**"));

		assertThat(low).isGreaterThan(0);
	}

	// --- 3. ingesta incremental: FIQL y sort sobre las fechas -------------------------------------------

	/**
	 * La ingesta incremental necesita capturar altas <i>y</i> cierres. Las altas se filtran por
	 * {@code requested_datetime} (confirmado en S0.3); los cierres solo se pueden capturar si
	 * {@code updated_datetime} admite FIQL o al menos {@code sort}.
	 */
	@Test
	@Order(3)
	void incrementalFilters() {
		heading(ID, "3. Ingesta incremental: filtros y orden por fecha");

		record Probe(String label, Map<String, String> params) {
		}

		List<Probe> probes = List.of(
				new Probe("FIQL `requested_datetime=ge=` (S0.3, control)",
						params("rows", "5", "q", "requested_datetime=ge=2026-09-01T00:00:00Z")),
				new Probe("FIQL `updated_datetime=ge=`",
						params("rows", "5", "q", "updated_datetime=ge=2026-09-01T00:00:00Z")),
				new Probe("FIQL `updated_datetime=ge=` + `sort=updated_datetime asc`",
						params("rows", "5", "q", "updated_datetime=ge=2026-09-01T00:00:00Z", "sort",
								"updated_datetime asc")),
				new Probe("`sort=updated_datetime desc`", params("rows", "5", "sort", "updated_datetime desc")),
				new Probe("`sort=requested_datetime desc` (S0.3, control)",
						params("rows", "5", "sort", "requested_datetime desc")),
				new Probe("FIQL ventana cerrada de un mes",
						params("rows", "5", "q",
								"requested_datetime=ge=2024-03-01T00:00:00Z;requested_datetime=lt=2024-04-01T00:00:00Z")),
				new Probe("FIQL ventana + `start` profundo",
						params("rows", "5", "start", "2000", "q",
								"requested_datetime=ge=2024-01-01T00:00:00Z;requested_datetime=lt=2025-01-01T00:00:00Z")),
				new Probe("`status=closed` (S0.3, control)", params("rows", "5", "status", "closed")));

		var rows = new ArrayList<List<String>>();
		for (Probe probe : probes) {
			Response response = api.tryGet(listUrl(probe.params()));
			List<JsonNode> found = records(response);
			String sample = "-";
			if (!found.isEmpty()) {
				JsonNode f = found.get(0);
				sample = text(f, "requested_datetime") + " / " + text(f, "updated_datetime") + " / "
						+ text(f, "status");
			}
			rows.add(List.of(probe.label(), String.valueOf(response.status()), String.valueOf(found.size()), sample));
		}
		table(ID, List.of("sonda", "estado", "registros", "primer registro (requested / updated / status)"), rows);
		metric(ID, "\n(«primer registro» permite ver si el filtro y el orden se aplicaron de verdad o se ignoraron.)");
	}

	// --- 4. cobertura territorial y variantes de nombre a lo largo del histórico ------------------------

	/**
	 * S0.3 midió cobertura sobre los 500 registros más recientes. Para el producto importa el histórico: una
	 * muestra por año dice si la geolocalización es reciente o viene de siempre, y qué nombres de junta aparecen
	 * (ADR-011 §5: un sinónimo se escribe con evidencia, no de memoria).
	 */
	@Test
	@Order(4)
	void territorialCoverageByYear() {
		heading(ID, "4. Cobertura territorial por año (muestra de " + ROWS + " por año)");

		var districtNames = new TreeMap<String, Integer>();
		var statusCounts = new TreeMap<String, Integer>();
		var rows = new ArrayList<List<String>>();
		var personalDataRows = new ArrayList<List<String>>();
		int totalSampled = 0;
		int totalWithText = 0;

		for (int year = 2013; year <= 2026; year++) {
			String window = "requested_datetime=ge=" + year + "-01-01T00:00:00Z;requested_datetime=lt=" + (year + 1)
					+ "-01-01T00:00:00Z";
			Response response = api.tryGet(listUrl(params("rows", String.valueOf(ROWS), "srsname", "wgs84", "q",
					window, "sort", "requested_datetime asc")));
			List<JsonNode> sample = records(response);
			totalSampled += sample.size();

			int withGeometry = 0;
			int withDistrict = 0;
			int withAddress = 0;
			int closed = 0;
			int withUpdated = 0;
			var textStats = new TextStats();
			for (JsonNode record : sample) {
				if (!record.path("geometry").isMissingNode() && !record.path("geometry").isNull()) {
					withGeometry++;
				}
				String district = text(record, "district");
				if (!district.isBlank()) {
					withDistrict++;
					districtNames.merge(district, 1, Integer::sum);
				}
				if (!text(record, "address_string").isBlank()) {
					withAddress++;
				}
				String status = text(record, "status");
				if (!status.isBlank()) {
					statusCounts.merge(status, 1, Integer::sum);
				}
				if ("closed".equals(status)) {
					closed++;
				}
				if (!text(record, "updated_datetime").isBlank()) {
					withUpdated++;
				}
				textStats.scan(record);
			}
			totalWithText += textStats.withText;

			rows.add(List.of(String.valueOf(year), String.valueOf(sample.size()), pct(withGeometry, sample.size()),
					pct(withDistrict, sample.size()), pct(withAddress, sample.size()), pct(closed, sample.size()),
					pct(withUpdated, sample.size())));
			personalDataRows.add(List.of(String.valueOf(year), String.valueOf(textStats.withText),
					String.valueOf(textStats.dni), String.valueOf(textStats.email), String.valueOf(textStats.phone),
					String.valueOf(textStats.plate), String.valueOf(textStats.signature),
					String.valueOf(textStats.anyPattern), String.valueOf(textStats.maxLength)));
		}

		table(ID, List.of("año", "muestra", "con geometría", "con district", "con dirección", "cerradas",
				"con updated"), rows);

		heading(ID, "4.1 Nombres de junta que publica la fuente (`district`)");
		metric(ID, "Valores distintos: **" + districtNames.size() + "** sobre " + totalSampled + " registros"
				+ " muestreados. Se comparan con los 29 nombres oficiales en el test 5.");
		counts(ID, "district", districtNames);

		heading(ID, "4.2 Estados");
		counts(ID, "status", statusCounts);

		heading(ID, "4.3 Texto libre: recuento de patrones de dato personal (nunca el texto, regla 22)");
		metric(ID, "Registros con algún campo de texto: **" + totalWithText + "** de " + totalSampled + ".");
		table(ID, List.of("año", "con texto", "DNI/NIE", "correo", "teléfono", "matrícula", "firma",
				"algún patrón", "long. máx."), personalDataRows);

		assertThat(totalSampled).isGreaterThan(0);
	}

	static String pct(int n, int total) {
		if (total == 0) {
			return "-";
		}
		return n + " (" + Math.round(100.0 * n / total) + " %)";
	}

	/**
	 * Recuento de patrones de dato personal sobre el texto libre. No guarda ni imprime ninguna coincidencia:
	 * solo cuenta (regla 22).
	 */
	static final class TextStats {

		static final Pattern DNI = Pattern.compile("\\b(?:[0-9]{8}|[XYZxyz][0-9]{7})[ -]?[A-Za-z]\\b");
		static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[A-Za-z]{2,}");
		static final Pattern PHONE = Pattern.compile("(?<![0-9])[679][0-9]{2}[ .-]?[0-9]{3}[ .-]?[0-9]{3}(?![0-9])");
		static final Pattern PLATE = Pattern
				.compile("\\b[0-9]{4}[ -]?[BCDFGHJKLMNPRSTVWXYZbcdfghjklmnpqrstvwxyz]{3}\\b");
		static final Pattern SIGNATURE = Pattern.compile(
				"(?i)\\b(atentamente|un saludo|saludos cordiales|me llamo|mi nombre es|firmado|fdo\\.?|soy la vecina|soy el vecino)\\b");

		int withText;
		int dni;
		int email;
		int phone;
		int plate;
		int signature;
		int anyPattern;
		int maxLength;

		void scan(JsonNode record) {
			var joined = new StringBuilder();
			for (String field : CITIZEN_TEXT) {
				String value = text(record, field);
				if (!value.isBlank()) {
					joined.append(value).append('\n');
				}
			}
			if (joined.isEmpty()) {
				return;
			}
			String text = joined.toString();
			withText++;
			maxLength = Math.max(maxLength, text.length());
			boolean any = false;
			if (DNI.matcher(text).find()) {
				dni++;
				any = true;
			}
			if (EMAIL.matcher(text).find()) {
				email++;
				any = true;
			}
			if (PHONE.matcher(text).find()) {
				phone++;
				any = true;
			}
			if (PLATE.matcher(text).find()) {
				plate++;
				any = true;
			}
			if (SIGNATURE.matcher(text).find()) {
				signature++;
				any = true;
			}
			if (any) {
				anyPattern++;
			}
		}
	}

	// --- 5. sinónimos de nombre de junta ----------------------------------------------------------------

	/**
	 * Casa los nombres que publica la fuente contra los 29 oficiales de {@code distrito.json} por clave
	 * normalizada. Lo que no case es exactamente la lista de sinónimos que ADR-011 §5 manda documentar.
	 */
	@Test
	@Order(5)
	void districtNameSynonyms() {
		heading(ID, "5. Nombres de junta: qué casa y qué no");

		Response districts = api.get(SEDE + "/distrito.json?srsname=wgs84&rows=100");
		var official = new LinkedHashMap<String, String>(); // clave normalizada -> "id title"
		for (JsonNode district : districts.json().path("result")) {
			official.put(normalize(text(district, "title")),
					district.path("id").asInt() + " " + text(district, "title"));
		}
		metric(ID, "- juntas oficiales: " + official.size());

		// Nombres tal y como los publica la fuente de quejas, sobre una muestra amplia y reciente.
		var fromSource = new TreeMap<String, Integer>();
		for (int start = 0; start < 2000; start += ROWS) {
			Response page = api.tryGet(listUrl(params("rows", String.valueOf(ROWS), "start", String.valueOf(start),
					"sort", "requested_datetime desc")));
			for (JsonNode record : records(page)) {
				String district = text(record, "district");
				if (!district.isBlank()) {
					fromSource.merge(district, 1, Integer::sum);
				}
			}
		}

		var rows = new ArrayList<List<String>>();
		int matched = 0;
		for (Map.Entry<String, Integer> entry : fromSource.entrySet()) {
			String key = normalize(entry.getKey());
			String official1 = official.get(key);
			if (official1 != null) {
				matched++;
			}
			rows.add(List.of(describe(entry.getKey()), String.valueOf(entry.getValue()), key,
					official1 == null ? "**sin correspondencia**" : official1));
		}
		table(ID, List.of("`district` de la fuente", "n", "clave normalizada", "junta oficial"), rows);
		metric(ID, "\n- casan por clave normalizada: **" + matched + " de " + fromSource.size() + "**; el resto es la"
				+ " lista de sinónimos que hay que documentar (ADR-011 §5).");

		assertThat(fromSource).isNotEmpty();
	}

	/**
	 * Clave de comparación de ADR-011 §5: mayúsculas, sin tildes, sin el prefijo «Junta Municipal/Vecinal», sin
	 * artículo inicial y sin signos.
	 */
	static String normalize(String name) {
		String value = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		value = value.toUpperCase(Locale.ROOT);
		value = value.replaceAll("[^A-Z0-9 ]", " ");
		value = value.replaceAll("\\b(JUNTA)?\\s*(MUNICIPAL|VECINAL)\\b", " ");
		value = value.replaceAll("^\\s*(EL|LA|LOS|LAS)\\b", " ");
		return value.replaceAll("\\s+", " ").trim();
	}

	/** Texto seguro para el informe: los caracteres no imprimibles se muestran como escape (S2.1). */
	static String describe(String value) {
		var out = new StringBuilder();
		value.codePoints().forEach(cp -> {
			if (Character.isISOControl(cp) || Character.getType(cp) == Character.CONTROL
					|| Character.getType(cp) == Character.FORMAT) {
				out.append(String.format("<U+%04X>", cp));
			}
			else {
				out.appendCodePoint(cp);
			}
		});
		return out.toString();
	}

	// --- 6. taxonomía y fixtures ------------------------------------------------------------------------

	/** Taxonomía de servicios y el reparto real de categorías; graba los fixtures de la ingesta. */
	@Test
	@Order(6)
	void taxonomyAndFixtures() {
		heading(ID, "6. Taxonomía y fixtures");

		Response services = api.get(ZaragozaSpikeClient.OPEN311 + "/services.json");
		metric(ID, "- `open311/services.json`: " + services.summary() + " → " + services.json().size() + " servicios");
		SpikeFixtures.save(FIXTURES, "services.json", services.body());

		// Página de ingesta tal y como la pedirá producción: los 8 campos de ADR-012, sin texto libre ni
		// address_string (ADR-011 §2 prohíbe geocodificar por dirección, así que no tiene uso).
		String projection = "service_request_id,status,service_code,service_name,requested_datetime,"
				+ "updated_datetime,geometry,district";
		Response page = api.get(listUrl(params("rows", String.valueOf(ROWS), "srsname", "wgs84", "fl", projection,
				"sort", "requested_datetime desc")));
		metric(ID, "- página de ingesta (`fl` + `sort=requested_datetime desc`): " + page.summary() + " → "
				+ records(page).size() + " registros");
		SpikeFixtures.saveRedacted(FIXTURES, "sede-list-ingest-page.json", page.body(), CITIZEN_TEXT);
		SpikeFixtures.saveHeaders(FIXTURES, "sede-list-ingest-page.headers", page.status(), page.headers());

		var serviceCounts = new TreeMap<String, Integer>();
		for (JsonNode record : records(page)) {
			serviceCounts.merge(text(record, "service_name"), 1, Integer::sum);
		}
		metric(ID, "- categorías distintas en la página: " + serviceCounts.size());
		var top = new LinkedHashMap<String, Integer>();
		serviceCounts.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.limit(15)
				.forEach(e -> top.put(e.getKey(), e.getValue()));
		counts(ID, "service_name (top 15)", top);

		assertThat(records(page)).isNotEmpty();
	}

	// --- 7. la forma exacta de las peticiones de producción ---------------------------------------------

	/**
	 * Las sondas anteriores prueban los parámetros por separado. Producción los usa <b>juntos</b> ({@code fl} +
	 * {@code sort} + {@code q} + {@code start} profundo) y de eso depende que la paginación no se salte ni repita
	 * registros: se comprueba tal cual, no por analogía (regla 1).
	 */
	@Test
	@Order(7)
	void productionRequestShapes() {
		heading(ID, "7. Las peticiones tal y como las hará producción");

		String projection = "service_request_id,status,service_code,service_name,requested_datetime,"
				+ "updated_datetime,geometry,district";

		// Carga inicial: orden ascendente estable + start creciente.
		var rows = new ArrayList<List<String>>();
		for (String start : List.of("0", "44500", "89000", "89430")) {
			Map<String, String> query = params("rows", "5", "srsname", "wgs84", "fl", projection, "sort",
					"requested_datetime asc", "start", start);
			Response first = api.tryGet(listUrl(query));
			Response second = api.tryGet(listUrl(query));
			List<JsonNode> a = records(first);
			List<JsonNode> b = records(second);
			String ids = a.stream().map(r -> text(r, "service_request_id")).reduce((x, y) -> x + "," + y).orElse("-");
			String idsAgain = b.stream().map(r -> text(r, "service_request_id")).reduce((x, y) -> x + "," + y)
					.orElse("-");
			rows.add(List.of("`start=" + start + "`", String.valueOf(first.status()), String.valueOf(a.size()),
					a.isEmpty() ? "-" : text(a.get(0), "requested_datetime"), ids.equals(idsAgain) ? "sí" : "**no**"));
		}
		table(ID, List.of("sonda (carga inicial, `sort=requested_datetime asc`)", "estado", "registros",
				"primer `requested_datetime`", "repetible"), rows);

		// Solape entre páginas consecutivas: la 500 y la 501 no deben compartir ningún id.
		Map<String, String> pageOne = params("rows", "500", "srsname", "wgs84", "fl", projection, "sort",
				"requested_datetime asc", "start", "1000");
		Map<String, String> pageTwo = params("rows", "500", "srsname", "wgs84", "fl", projection, "sort",
				"requested_datetime asc", "start", "1500");
		var idsOne = new java.util.LinkedHashSet<String>();
		records(api.get(listUrl(pageOne))).forEach(r -> idsOne.add(text(r, "service_request_id")));
		var idsTwo = new java.util.LinkedHashSet<String>();
		records(api.get(listUrl(pageTwo))).forEach(r -> idsTwo.add(text(r, "service_request_id")));
		var overlap = new java.util.LinkedHashSet<>(idsOne);
		overlap.retainAll(idsTwo);
		metric(ID, "- páginas consecutivas (`start=1000` y `start=1500`, rows=500): " + idsOne.size() + " y "
				+ idsTwo.size() + " ids distintos, **solape = " + overlap.size() + "**");

		// Incremental de altas y de cierres: tamaño de la ventana de una semana.
		var incremental = new ArrayList<List<String>>();
		for (String field : List.of("requested_datetime", "updated_datetime")) {
			Map<String, String> query = params("rows", "500", "srsname", "wgs84", "fl", projection, "q",
					field + "=ge=2026-09-01T00:00:00Z", "sort", field + " asc");
			Response response = api.tryGet(listUrl(query));
			List<JsonNode> found = records(response);
			Response deep = api.tryGet(listUrl(params("rows", "5", "srsname", "wgs84", "fl", projection, "q",
					field + "=ge=2026-09-01T00:00:00Z", "sort", field + " asc", "start", "500")));
			incremental.add(List.of("`" + field + "=ge=2026-09-01`", String.valueOf(response.status()),
					String.valueOf(found.size()), found.isEmpty() ? "-" : text(found.get(0), field),
					String.valueOf(records(deep).size())));
		}
		table(ID, List.of("ventana incremental (una semana)", "estado", "registros en la 1.ª página",
				"primer valor", "registros en `start=500`"), incremental);
		metric(ID, "\n(«registros en `start=500`» dice si la ventana semanal cabe en una página o hay que paginar.)");
	}

}
