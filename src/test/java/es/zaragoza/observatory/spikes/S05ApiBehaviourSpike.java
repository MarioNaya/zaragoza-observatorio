package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.DATA_SPACE;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.OPEN311;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import es.zaragoza.observatory.spikes.support.SpikeFixtures;
import es.zaragoza.observatory.spikes.support.SpikeJson;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S0.5 — Comportamiento de la API (SPEC.md §3): latencias, tope de rows, semántica de start, negociación de formato,
 * cabeceras condicionales (¿se honran?), formatos de fecha, forma de los errores, ráfagas. Informe: docs/spikes/S0.5-api.md.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S05ApiBehaviourSpike {

	static final String ID = "S0.5-api";
	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient();

	/** Endpoints representativos (listados). Todos verificados en spikes anteriores o en el Swagger. */
	static final Map<String, String> LISTS = new LinkedHashMap<>();
	static {
		LISTS.put("catalogo", DATA_SPACE + "/catalogo.json?fl=id,modified");
		LISTS.put("distrito", SEDE + "/distrito.json?srsname=wgs84");
		LISTS.put("quejas-list", SEDE + "/quejas-sugerencias/list.json?fl=service_request_id,requested_datetime");
		LISTS.put("ocds-list", SEDE + "/contratacion-publica/ocds/contracting-process.json");
		LISTS.put("presupuesto-gasto", SEDE + "/presupuesto/gasto-corriente.json");
		LISTS.put("licencia-obra", SEDE + "/licencia-obra.json?srsname=wgs84");
		LISTS.put("equipamiento", SEDE + "/equipamiento.json");
		LISTS.put("via-publica", SEDE + "/via-publica/incidencia.json");
		LISTS.put("open311-requests", OPEN311 + "/requests.json");
	}

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
	}

	@Test
	@Order(1)
	void responseShapesAndRowsLimits() {
		heading(ID, "Forma de la respuesta, tope de rows y semántica de start");
		List<List<String>> rows = new ArrayList<>();
		for (var e : LISTS.entrySet()) {
			String base = e.getValue();
			Response r50 = api.get(with(base, "rows=50"));
			Response r500 = api.get(with(base, "rows=500"));
			Response r1000 = api.get(with(base, "rows=1000"));
			Response s10 = api.get(with(base, "rows=5&start=10"));
			Response s0 = api.get(with(base, "rows=5&start=0"));
			String shape = shape(r50);
			String startWorks = firstId(s10).isEmpty() || firstId(s0).isEmpty() ? "?" : (firstId(s10).equals(firstId(s0)) ? "NO (mismo primero)" : "sí");
			rows.add(List.of(e.getKey(), String.valueOf(r50.status()), shape, String.valueOf(count(r50)), String.valueOf(count(r500)),
					String.valueOf(count(r1000)), totalCount(r500), startWorks, r1000.elapsed().toMillis() + " ms"));
		}
		table(ID, List.of("endpoint", "status", "forma", "rows=50", "rows=500", "rows=1000", "totalCount", "start desplaza", "t(rows=1000)"), rows);
	}

	@Test
	@Order(2)
	void headersAndConditionalRequests() {
		heading(ID, "Cabeceras de caché y peticiones condicionales (¿se honran ETag / Last-Modified?)");
		Map<String, String> targets = new LinkedHashMap<>();
		targets.put("catalogo list", DATA_SPACE + "/catalogo.json?rows=1&fl=id");
		targets.put("catalogo detail", DATA_SPACE + "/catalogo/13.json");
		targets.put("distrito list", SEDE + "/distrito/municipal.json?srsname=wgs84");
		targets.put("distrito detail", SEDE + "/distrito/1.json");
		targets.put("quejas list", SEDE + "/quejas-sugerencias/list.json?rows=1");
		targets.put("ocds list", SEDE + "/contratacion-publica/ocds/contracting-process.json?rows=1");
		targets.put("presupuesto list", SEDE + "/presupuesto/gasto-corriente.json?rows=1");
		targets.put("licencia-obra list", SEDE + "/licencia-obra.json?rows=1");
		targets.put("equipamiento list", SEDE + "/equipamiento.json?rows=1");
		targets.put("open311 requests", OPEN311 + "/requests.json?status=open");
		targets.put("open311 services", OPEN311 + "/services.json");
		String quejaId = firstId(api.get(SEDE + "/quejas-sugerencias/list.json?rows=1&fl=service_request_id"));
		if (!quejaId.isEmpty()) {
			targets.put("quejas detail", SEDE + "/quejas-sugerencias/" + quejaId + ".json");
		}
		String equipId = firstId(api.get(SEDE + "/equipamiento.json?rows=1&fl=id"));
		if (!equipId.isEmpty()) {
			targets.put("equipamiento detail", SEDE + "/equipamiento/" + equipId + ".json");
		}
		List<List<String>> rows = new ArrayList<>();
		for (var e : targets.entrySet()) {
			Response r = api.get(e.getValue());
			String etag = r.header("ETag");
			String lastModified = r.header("Last-Modified");
			String inm = etag == null ? "-" : String.valueOf(api.get(e.getValue(), h -> h.set("If-None-Match", etag)).status());
			String imsRaw = lastModified == null ? "-" : String.valueOf(api.get(e.getValue(), h -> h.set("If-Modified-Since", lastModified)).status());
			String imsRfc = String.valueOf(api.get(e.getValue(), h -> h.set("If-Modified-Since",
					DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC)))).status());
			rows.add(List.of(e.getKey(), String.valueOf(r.status()), nz(etag), nz(lastModified), parseable(lastModified),
					nz(r.header("Cache-Control")), nz(r.header("Expires")), inm, imsRaw, imsRfc));
		}
		table(ID, List.of("endpoint", "status", "ETag", "Last-Modified", "LM parseable", "Cache-Control", "Expires",
				"If-None-Match→", "If-Modified-Since(raw)→", "If-Modified-Since(ahora RFC)→"), rows);
	}

	@Test
	@Order(3)
	void formatNegotiation() {
		heading(ID, "Negociación de formato: extensión frente a cabecera Accept");
		Map<String, String> bases = new LinkedHashMap<>();
		bases.put("distrito/municipal", SEDE + "/distrito/municipal");
		bases.put("quejas-sugerencias/list", SEDE + "/quejas-sugerencias/list");
		bases.put("ocds/contracting-process", SEDE + "/contratacion-publica/ocds/contracting-process");
		bases.put("presupuesto/gasto-corriente", SEDE + "/presupuesto/gasto-corriente");
		bases.put("catalogo (espacio de datos)", DATA_SPACE + "/catalogo");
		List<List<String>> rows = new ArrayList<>();
		for (var e : bases.entrySet()) {
			String b = e.getValue();
			rows.add(List.of(e.getKey(), st(api.get(b + ".json?rows=1")), st(api.get(b + "?rows=1")),
					st(api.get(b + "?rows=1", h -> h.set("Accept", "application/json"))),
					st(api.get(b + ".csv?rows=1")), st(api.get(b + ".geojson?rows=1")), st(api.get(b + ".xml?rows=1"))));
		}
		table(ID, List.of("endpoint", ".json", "sin ext.", "sin ext. + Accept json", ".csv", ".geojson", ".xml"), rows);
	}

	@Test
	@Order(4)
	void dateFormatsAndQueryFeatures() {
		heading(ID, "Formatos de fecha en las respuestas y soporte de sort / FIQL (q)");
		record Probe(String label, String url, String field) {
		}
		List<Probe> probes = List.of(
				new Probe("quejas requested_datetime", SEDE + "/quejas-sugerencias/list.json?rows=1&fl=requested_datetime", "requested_datetime"),
				new Probe("presupuesto fecha", SEDE + "/presupuesto/gasto-corriente.json?rows=1", "fecha"),
				new Probe("licencia-obra lastUpdated", SEDE + "/licencia-obra.json?rows=1", "lastUpdated"),
				new Probe("equipamiento lastUpdated", SEDE + "/equipamiento.json?rows=1", "lastUpdated"),
				new Probe("via-publica inicio", SEDE + "/via-publica/incidencia.json?rows=1", "inicio"),
				new Probe("catalogo modified", DATA_SPACE + "/catalogo.json?rows=1&fl=modified", "modified"),
				new Probe("open311 requested_datetime", OPEN311 + "/requests.json?status=open", "requested_datetime"));
		for (Probe p : probes) {
			Response r = api.get(p.url());
			List<JsonNode> items = items(r);
			String raw = items.isEmpty() ? "-" : text(items.get(0), p.field());
			metric(ID, "- " + p.label() + " -> " + r.status() + " valor=`" + raw + "` parse=" + (SpikeJson.date(items.isEmpty() ? null : items.get(0).path(p.field())) != null));
		}
		heading(ID, "sort y q (FIQL)");
		for (String q : List.of(SEDE + "/quejas-sugerencias/list.json?rows=2&fl=service_request_id,requested_datetime&sort=requested_datetime%20asc",
				SEDE + "/quejas-sugerencias/list.json?rows=2&fl=service_request_id,requested_datetime&sort=requested_datetime%20desc",
				SEDE + "/quejas-sugerencias/list.json?rows=2&fl=service_request_id,status&q=status==closed",
				SEDE + "/quejas-sugerencias/list.json?rows=2&fl=service_request_id,requested_datetime&q=requested_datetime=ge=2026-08-01T00:00:00Z",
				SEDE + "/equipamiento.json?rows=2&fl=id,title,lastUpdated&sort=lastUpdated%20desc",
				SEDE + "/equipamiento.json?rows=2&fl=id,title,lastUpdated&q=lastUpdated=ge=2026-08-01T00:00:00Z",
				SEDE + "/presupuesto/gasto-corriente.json?rows=2&sort=fecha%20desc&fl=id,fecha",
				SEDE + "/licencia-obra.json?rows=2&fl=id,lastUpdated&sort=lastUpdated%20desc")) {
			Response r = api.get(q);
			List<JsonNode> items = items(r);
			metric(ID, "- `" + q.replace(SEDE, "…") + "` -> " + r.status() + " n=" + items.size() + " primero=" + (items.isEmpty() ? snippet(r.body()) : items.get(0).toString()));
		}
	}

	@Test
	@Order(5)
	void latencyAndBurst() throws Exception {
		heading(ID, "Latencia secuencial (40 peticiones) y ráfaga concurrente (10 en paralelo)");
		String small = SEDE + "/quejas-sugerencias/list.json?rows=1&fl=service_request_id";
		List<Long> ms = new ArrayList<>();
		Map<String, Integer> statuses = new TreeMap<>();
		for (int i = 0; i < 40; i++) {
			Response r = api.get(small + "&start=" + i);
			ms.add(r.elapsed().toMillis());
			statuses.merge(String.valueOf(r.status()), 1, Integer::sum);
		}
		ms.sort(Long::compare);
		metric(ID, "secuencial: estados=" + statuses + " ms p50=" + SpikeJson.percentile(ms, 0.5) + " p90=" + SpikeJson.percentile(ms, 0.9) + " máx=" + SpikeJson.percentile(ms, 1.0));
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Callable<Response>> tasks = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				final int start = 100 + i;
				tasks.add(() -> api.get(small + "&start=" + start));
			}
			long t0 = System.nanoTime();
			List<Future<Response>> futures = pool.invokeAll(tasks);
			Map<String, Integer> concurrent = new TreeMap<>();
			List<Long> cms = new ArrayList<>();
			for (Future<Response> f : futures) {
				Response r = f.get();
				concurrent.merge(String.valueOf(r.status()), 1, Integer::sum);
				cms.add(r.elapsed().toMillis());
			}
			cms.sort(Long::compare);
			metric(ID, "concurrente x10: estados=" + concurrent + " ms p50=" + SpikeJson.percentile(cms, 0.5) + " máx=" + SpikeJson.percentile(cms, 1.0) + " total=" + (System.nanoTime() - t0) / 1_000_000 + " ms");
		}
		Response big = api.get(SEDE + "/quejas-sugerencias/list.json?rows=500&srsname=wgs84");
		metric(ID, "página grande quejas rows=500: " + big.status() + " bytes=" + big.body().length() + " ms=" + big.elapsed().toMillis());
		Response bigOcds = api.get(SEDE + "/contratacion-publica/ocds/contracting-process.json?rows=5000");
		metric(ID, "OCDS rows=5000: " + bigOcds.status() + " bytes=" + bigOcds.body().length() + " n=" + count(bigOcds) + " ms=" + bigOcds.elapsed().toMillis());
	}

	@Test
	@Order(6)
	void errorShapes() {
		heading(ID, "Forma de los errores (para el anti-corruption layer)");
		for (String u : List.of(SEDE + "/distrito/999999.json", SEDE + "/distrito.json?rows=abc", SEDE + "/no-existe.json",
				SEDE + "/quejas-sugerencias/list.json?rows=5&q=campo_inexistente==1", SEDE + "/contratacion-publica/ocds/contracting-process/xxx.json",
				DATA_SPACE + "/catalogo/999999.json", OPEN311 + "/requests/1.json")) {
			Response r = api.get(u);
			metric(ID, "- `" + u.replace(SEDE, "…").replace(DATA_SPACE, "…/espacio-de-datos") + "` -> " + r.status() + " " + r.contentType() + " " + snippet(r.body()));
		}
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	static String with(String base, String params) {
		return base + (base.contains("?") ? "&" : "?") + params;
	}

	static String shape(Response r) {
		if (r.status() != 200 || !r.isJson()) {
			return r.status() + " " + r.contentType();
		}
		JsonNode n = r.json();
		if (n.isArray()) {
			return "array";
		}
		if (n.has("result")) {
			return "{totalCount,result}";
		}
		if (n.has("features")) {
			return "FeatureCollection";
		}
		return "objeto " + new java.util.TreeSet<>(n.propertyNames());
	}

	static List<JsonNode> items(Response r) {
		List<JsonNode> out = new ArrayList<>();
		if (r.status() == 200 && r.isJson()) {
			JsonNode n = r.json();
			JsonNode arr = n.isArray() ? n : n.has("result") ? n.path("result") : n.has("features") ? n.path("features") : n;
			if (arr.isArray()) {
				arr.forEach(out::add);
			}
		}
		return out;
	}

	static int count(Response r) {
		return r.status() == 200 && r.isJson() ? items(r).size() : -1;
	}

	static String totalCount(Response r) {
		return r.status() == 200 && r.isJson() && r.json().has("totalCount") ? text(r.json(), "totalCount") : "-";
	}

	static String firstId(Response r) {
		List<JsonNode> items = items(r);
		if (items.isEmpty()) {
			return "";
		}
		JsonNode f = items.get(0);
		for (String k : List.of("id", "service_request_id", "ocid")) {
			if (f.has(k)) {
				return text(f, k);
			}
		}
		return f.toString();
	}

	static String st(Response r) {
		return r.status() + " " + r.contentType().replace(";charset=UTF-8", "");
	}

	static String nz(String s) {
		return s == null ? "-" : s;
	}

	static String parseable(String lastModified) {
		if (lastModified == null) {
			return "-";
		}
		try {
			DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModified);
			return "RFC 1123";
		}
		catch (RuntimeException e) {
			try {
				DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH).parse(lastModified);
				return "solo con patrón zzz";
			}
			catch (RuntimeException e2) {
				return "NO";
			}
		}
	}

	static String snippet(String body) {
		String s = body.replaceAll("\\s+", " ");
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}

}
