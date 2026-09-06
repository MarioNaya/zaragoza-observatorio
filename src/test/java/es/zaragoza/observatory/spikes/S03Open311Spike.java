package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.OPEN311;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import es.zaragoza.observatory.spikes.support.SpikeFixtures;
import es.zaragoza.observatory.spikes.support.SpikeJson;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import tools.jackson.databind.JsonNode;

/**
 * S0.3 — Quejas y sugerencias: Open311 y endpoint de sede (SPEC.md §3). Informe: docs/spikes/S0.3-open311.md.
 * <p>
 * Preguntas: endpoint real, campos, geolocalización, fecha de cierre (tiempo de respuesta), taxonomía, volumen y
 * profundidad histórica. Compara {@code /api/recurso/open311/requests.json} con
 * {@code /sede/servicio/quejas-sugerencias/list.json}.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S03Open311Spike {

	/** Campos escritos por ciudadanos o dirigidos a ellos: se redactan al guardar el fixture (regla 22, S0.3 adenda). */
	static final java.util.Set<String> CITIZEN_TEXT = java.util.Set.of("title", "description", "service_notice");

	static final String ID = "S0.3-open311";
	static final String QUEJAS = SEDE + "/quejas-sugerencias";

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient();

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
	}

	@Test
	@Order(1)
	void servicesTaxonomy() {
		heading(ID, "Open311 `services.json` (taxonomía)");
		var r = api.get(OPEN311 + "/services.json");
		metric(ID, r.summary());
		if (r.status() == 200 && r.isJson()) {
			SpikeFixtures.save("open311", "services.json", r.body());
			JsonNode arr = r.json();
			Map<String, Integer> types = new TreeMap<>();
			Map<String, Integer> metadata = new TreeMap<>();
			int withDescription = 0;
			for (JsonNode s : arr) {
				types.merge(text(s, "type"), 1, Integer::sum);
				metadata.merge(text(s, "metadata"), 1, Integer::sum);
				if (!text(s, "description").isBlank()) {
					withDescription++;
				}
			}
			metric(ID, "servicios=" + arr.size() + " tipos=" + types + " metadata=" + metadata + " con descripción="
					+ withDescription);
			metric(ID, "caminos=" + SpikeJson.paths(arr.get(0)));
		}
		var def = api.get(OPEN311 + "/services/250.json");
		metric(ID, "service definition 250 -> " + def.status() + " " + snippet(def.body()));
	}

	@Test
	@Order(2)
	void requestsDefaultPageAndPaging() {
		heading(ID, "Open311 `requests.json`: página por defecto y paginación");
		var r = api.get(OPEN311 + "/requests.json");
		metric(ID, r.summary());
		SpikeFixtures.saveRedacted("open311", "requests-default.json", r.body(), CITIZEN_TEXT);
		List<JsonNode> items = items(r);
		metric(ID, "devueltos=" + items.size());
		describe(items, "página por defecto");
		String firstId = items.isEmpty() ? "" : text(items.get(0), "service_request_id");
		for (String q : List.of("?page=2", "?start=50", "?start=50&rows=50", "?rows=500", "?page_size=500",
				"?status=open", "?status=closed", "?service_code=250")) {
			var p = api.get(OPEN311 + "/requests" + ".json" + q);
			List<JsonNode> pi = items(p);
			metric(ID, "`" + q + "` -> " + p.status() + " devueltos=" + pi.size() + " primero="
					+ (pi.isEmpty() ? "-" : text(pi.get(0), "service_request_id")) + (pi.isEmpty() ? ""
							: (text(pi.get(0), "service_request_id").equals(firstId) ? " (= primero por defecto)" : " (distinto)"))
					+ " ms=" + p.elapsed().toMillis());
		}
		if (!firstId.isEmpty()) {
			var d = api.get(OPEN311 + "/requests/" + firstId + ".json");
			metric(ID, "detalle requests/" + firstId + ".json -> " + d.status() + " caminos="
					+ (d.status() == 200 && d.isJson() ? SpikeJson.paths(d.json()) : List.of()));
			if (d.status() == 200) {
				SpikeFixtures.saveRedacted("open311", "request-" + firstId + ".json", d.body(), CITIZEN_TEXT);
			}
		}
	}

	@Test
	@Order(3)
	void historyDepthByDateWindows() {
		heading(ID, "Open311: profundidad histórica por ventanas start_date/end_date (año completo)");
		List<List<String>> rows = new ArrayList<>();
		for (int year = 2026; year >= 2004; year--) {
			var r = api.get(OPEN311 + "/requests.json?start_date=" + year + "-01-01T00:00:00Z&end_date=" + year
					+ "-12-31T23:59:59Z");
			List<JsonNode> items = items(r);
			rows.add(List.of(String.valueOf(year), String.valueOf(r.status()), String.valueOf(items.size()),
					items.isEmpty() ? "-" : text(items.get(items.size() - 1), "requested_datetime"),
					items.isEmpty() ? "-" : text(items.get(0), "requested_datetime"), String.valueOf(r.elapsed().toMillis())));
		}
		table(ID, List.of("año", "status", "devueltos", "más antiguo", "más reciente", "ms"), rows);

		heading(ID, "Open311: ¿la ventana está limitada? agosto 2026 completo vs semanas");
		var month = api.get(OPEN311 + "/requests.json?start_date=2026-08-01T00:00:00Z&end_date=2026-08-31T23:59:59Z");
		int monthly = items(month).size();
		int weeklySum = 0;
		LocalDate from = LocalDate.of(2026, 8, 1);
		while (from.getMonthValue() == 8) {
			LocalDate to = from.plusDays(6).isAfter(LocalDate.of(2026, 8, 31)) ? LocalDate.of(2026, 8, 31) : from.plusDays(6);
			var w = api.get(OPEN311 + "/requests.json?start_date=" + from + "T00:00:00Z&end_date=" + to + "T23:59:59Z");
			int n = items(w).size();
			weeklySum += n;
			metric(ID, "semana " + from + ".." + to + " -> " + n);
			from = to.plusDays(1);
		}
		metric(ID, "mes completo=" + monthly + " suma semanas=" + weeklySum + (monthly < weeklySum
				? " => la ventana mensual está TRUNCADA (hay tope por petición)" : " => sin tope aparente"));
	}

	@Test
	@Order(4)
	void closedRequestsAndResponseTime() {
		heading(ID, "Open311: cerradas de julio 2026 — campos y tiempo hasta updated_datetime");
		var r = api.get(OPEN311 + "/requests.json?status=closed&start_date=2026-07-01T00:00:00Z&end_date=2026-07-31T23:59:59Z");
		metric(ID, r.summary());
		List<JsonNode> items = items(r);
		SpikeFixtures.saveRedacted("open311", "requests-closed-2026-07.json", r.body(), CITIZEN_TEXT);
		describe(items, "cerradas julio 2026");
		List<Long> hours = new ArrayList<>();
		for (JsonNode i : items) {
			LocalDateTime req = SpikeJson.date(i.path("requested_datetime"));
			LocalDateTime upd = SpikeJson.date(i.path("updated_datetime"));
			if (req != null && upd != null) {
				hours.add(ChronoUnit.HOURS.between(req, upd));
			}
		}
		hours.sort(Long::compare);
		metric(ID, "horas requested->updated (n=" + hours.size() + "): p10=" + SpikeJson.percentile(hours, 0.1) + " p50="
				+ SpikeJson.percentile(hours, 0.5) + " p90=" + SpikeJson.percentile(hours, 0.9) + " máx="
				+ SpikeJson.percentile(hours, 1.0));
		var open = api.get(OPEN311 + "/requests.json?status=open&start_date=2026-07-01T00:00:00Z&end_date=2026-07-31T23:59:59Z");
		metric(ID, "abiertas de julio 2026 aún hoy=" + items(open).size());
	}

	@Test
	@Order(5)
	void sedeQuejasSugerenciasList() {
		heading(ID, "Sede `quejas-sugerencias/list.json`: paginación, orden, FIQL y formatos");
		var r = api.get(QUEJAS + "/list.json?rows=50");
		metric(ID, r.summary());
		SpikeFixtures.saveRedacted("open311", "sede-list-rows50.json", r.body(), CITIZEN_TEXT);
		List<JsonNode> items = itemsOfSede(r);
		metric(ID, "estructura raíz=" + (r.isJson() ? topLevel(r.json()) : "-") + " devueltos=" + items.size());
		describe(items, "sede rows=50");
		for (String q : List.of("?rows=500&fl=service_request_id", "?rows=1000&fl=service_request_id",
				"?rows=5&start=5&fl=service_request_id", "?rows=1&sort=requested_datetime%20asc",
				"?rows=1&sort=requested_datetime%20desc", "?rows=5&q=status==closed&fl=service_request_id,status",
				"?rows=5&status=closed&fl=service_request_id,status", "?rows=5&srsname=wgs84&fl=service_request_id,geometry",
				"?rows=5&barrio_code=1&fl=service_request_id,barrio_code,district",
				"?rows=5&start_date=2020-01-01&end_date=2020-01-31&fl=service_request_id,requested_datetime")) {
			var p = api.get(QUEJAS + "/list.json" + q);
			List<JsonNode> pi = itemsOfSede(p);
			String extra = "";
			if (!pi.isEmpty() && q.contains("sort=")) {
				extra = " requested_datetime=" + text(pi.get(0), "requested_datetime");
			}
			if (!pi.isEmpty() && (q.contains("geometry") || q.contains("barrio_code") || q.contains("status"))) {
				extra = " primero=" + pi.get(0);
			}
			metric(ID, "`" + q + "` -> " + p.status() + " devueltos=" + pi.size() + " totalCount="
					+ (p.isJson() && p.status() == 200 ? text(p.json(), "totalCount") : "-") + " ms=" + p.elapsed().toMillis()
					+ extra + (p.status() != 200 ? " body=" + snippet(p.body()) : ""));
		}
		var geojson = api.get(QUEJAS + "/list.geojson?rows=2&srsname=wgs84");
		metric(ID, geojson.summary() + " " + snippet(geojson.body()));
		if (!items.isEmpty()) {
			String id = text(items.get(0), "service_request_id");
			var d = api.get(QUEJAS + "/" + id + ".json");
			metric(ID, "detalle quejas-sugerencias/" + id + ".json -> " + d.status() + " caminos="
					+ (d.status() == 200 && d.isJson() ? SpikeJson.paths(d.json()) : List.of()));
			if (d.status() == 200) {
				SpikeFixtures.saveRedacted("open311", "sede-detail-" + id + ".json", d.body(), CITIZEN_TEXT);
			}
			var o = api.get(OPEN311 + "/requests/" + id + ".json");
			metric(ID, "mismo id en Open311 -> " + o.status() + (o.status() == 200 ? " (ids compartidos)" : ""));
		}
		var root = api.get(QUEJAS + ".json?rows=5");
		metric(ID, "`quejas-sugerencias.json?rows=5` -> " + root.status() + " raíz=" + (root.isJson() ? topLevel(root.json()) : "-")
				+ " " + snippet(root.body()));
		var stats = api.get(QUEJAS + "/statistics.json?year=2025");
		metric(ID, "`statistics.json?year=2025` -> " + stats.status() + " " + snippet(stats.body()));
		if (stats.status() == 200) {
			SpikeFixtures.save("open311", "sede-statistics-2025.json", stats.body());
		}
	}

	@Test
	@Order(6)
	void sedeGeoAndTerritoryCoverage() {
		heading(ID, "Sede: cobertura de geometría, barrio_code y district sobre 500 recientes");
		var r = api.get(QUEJAS
				+ "/list.json?rows=500&srsname=wgs84&sort=requested_datetime%20desc&fl=service_request_id,status,service_code,service_name,requested_datetime,updated_datetime,geometry,barrio_code,district,catSip,address_string");
		metric(ID, r.summary());
		List<JsonNode> items = itemsOfSede(r);
		SpikeFixtures.saveRedacted("open311", "sede-list-500-recent.json", r.body(), CITIZEN_TEXT);
		describe(items, "sede 500 recientes");
		Map<String, Integer> districts = new TreeMap<>();
		Map<String, Integer> barrios = new TreeMap<>();
		Map<String, Integer> services = new TreeMap<>();
		for (JsonNode i : items) {
			districts.merge(text(i, "district").isEmpty() ? "(vacío)" : text(i, "district"), 1, Integer::sum);
			barrios.merge(text(i, "barrio_code").isEmpty() ? "(vacío)" : text(i, "barrio_code"), 1, Integer::sum);
			services.merge(text(i, "service_name").isEmpty() ? "(vacío)" : text(i, "service_name"), 1, Integer::sum);
		}
		counts(ID, "district", districts);
		metric(ID, "barrio_code distintos=" + barrios.size() + " -> " + barrios);
		counts(ID, "service_name (top)", top(services, 25));
		if (!items.isEmpty()) {
			metric(ID, "ejemplo geometry=" + items.get(0).path("geometry"));
		}
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	static List<JsonNode> items(ZaragozaSpikeClient.Response r) {
		List<JsonNode> out = new ArrayList<>();
		if (r.status() == 200 && r.isJson()) {
			JsonNode n = r.json();
			if (n.isArray()) {
				n.forEach(out::add);
			}
		}
		return out;
	}

	static List<JsonNode> itemsOfSede(ZaragozaSpikeClient.Response r) {
		List<JsonNode> out = new ArrayList<>();
		if (r.status() == 200 && r.isJson()) {
			JsonNode n = r.json();
			JsonNode arr = n.isArray() ? n : n.has("result") ? n.path("result") : n.path("features");
			arr.forEach(out::add);
		}
		return out;
	}

	static void describe(List<JsonNode> items, String label) {
		if (items.isEmpty()) {
			metric(ID, label + ": sin elementos");
			return;
		}
		Map<String, Integer> coverage = SpikeJson.coverage(items);
		table(ID, List.of("camino (" + label + ", n=" + items.size() + ")", "no vacíos"), SpikeJson.rows(coverage, 60));
		Map<String, Integer> status = new TreeMap<>();
		LocalDateTime min = null, max = null;
		for (JsonNode i : items) {
			status.merge(text(i, "status"), 1, Integer::sum);
			LocalDateTime t = SpikeJson.date(i.path("requested_datetime"));
			if (t != null) {
				min = min == null || t.isBefore(min) ? t : min;
				max = max == null || t.isAfter(max) ? t : max;
			}
		}
		metric(ID, label + ": status=" + status + " requested_datetime min=" + min + " max=" + max);
	}

	static String topLevel(JsonNode n) {
		return n.isObject() ? new TreeSet<>(n.propertyNames()).toString() : n.getNodeType().name();
	}

	static Map<String, Integer> top(Map<String, Integer> counts, int limit) {
		Map<String, Integer> out = new LinkedHashMap<>();
		SpikeJson.rows(counts, limit).forEach(row -> out.put(row.get(0), Integer.parseInt(row.get(1))));
		return out;
	}

	static String snippet(String body) {
		String s = body.replaceAll("\\s+", " ");
		return s.length() > 220 ? s.substring(0, 220) + "…" : s;
	}

}
