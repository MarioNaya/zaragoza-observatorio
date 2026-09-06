package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.DATA_SPACE;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import es.zaragoza.observatory.spikes.support.SpikeJson;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S1.3 — Federación en datos.gob.es (docs/ESTADO.md §4 paso 2). Informe: docs/spikes/S1.3-federacion.md.
 * <p>
 * Preguntas: (a) cómo se pagina y qué forma tiene {@code apidata/catalog/dataset/publisher/L01502973.json}
 * (S0.1 lo recorrió en 8 páginas de 50) y si admite páginas mayores o cabeceras condicionales; (b) con qué campo
 * se enlaza cada dataset federado con la ficha municipal y con qué cobertura; (c) qué fichas del catálogo
 * municipal no están federadas y si eso se explica por {@code abierto} o {@code status}; (d) qué dice el
 * {@code modified} de la federación frente al del catálogo.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S13FederationSpike {

	static final String ID = "S1.3-federacion";
	static final String PUBLISHER = "L01502973";
	static final String DATOS_GOB = "https://datos.gob.es/apidata/catalog/dataset/publisher/" + PUBLISHER + ".json";
	static final String FIXTURES = "catalog";
	static final Pattern CATALOG_ID = Pattern.compile("/espacio-de-datos/servicio/catalogo/(\\d+)$");
	/** {@code mié, 14 abr 2010 22:00:00 GMT+0000}: día de la semana y mes abreviados en castellano. */
	static final Pattern SPANISH_DATE = Pattern.compile(
			"^\\p{L}+\\.?, (\\d{1,2}) (\\p{L}+)\\.? (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT([+-])(\\d{2})(\\d{2})$");
	static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("ene", 1), Map.entry("feb", 2),
			Map.entry("mar", 3), Map.entry("abr", 4), Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7),
			Map.entry("ago", 8), Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("oct", 10), Map.entry("nov", 11),
			Map.entry("dic", 12));

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(90));
	static final Map<Integer, JsonNode> catalog = new LinkedHashMap<>();
	static final List<JsonNode> federated = new ArrayList<>();
	static final List<Probe> probes = new ArrayList<>();
	static int pagesWalked;

	record Probe(String what, int status, long ms, long bytes) {
	}

	@BeforeAll
	static void loadCatalog() {
		SpikeFixtures.startMetrics(ID);
		heading(ID, "Catálogo municipal (fl reducido)");
		var r = api.get(DATA_SPACE + "/catalogo.json?rows=500&start=0&fl=id,title,abierto,status,modified,accrualPeriodicity,explorable");
		metric(ID, r.summary());
		assertThat(r.status()).isEqualTo(200);
		for (JsonNode d : r.json().path("result")) {
			catalog.put(d.path("id").asInt(), d);
		}
		metric(ID, "fichas=" + catalog.size());
	}

	@Test
	@Order(1)
	void pageSizeLimitsAndHeaders() {
		heading(ID, "Tamaño de página admitido por datos.gob.es (`_pageSize`)");
		List<List<String>> rows = new ArrayList<>();
		boolean headersSaved = false;
		for (int size : List.of(50, 100, 200, 500, 1000)) {
			Response r = api.tryGet(DATOS_GOB + "?_pageSize=" + size + "&_page=0");
			probes.add(new Probe("GET _pageSize=" + size, r.status(), r.elapsed().toMillis(), r.body().length()));
			JsonNode result = r.status() == 200 && r.isJson() ? tryJson(r).path("result") : null;
			rows.add(List.of(String.valueOf(size), String.valueOf(r.status()), r.contentType(),
					result == null ? "-" : text(result, "itemsPerPage"),
					result == null ? "-" : String.valueOf(result.path("items").size()),
					result == null ? "-" : (result.has("next") ? "sí" : "no"), String.valueOf(r.body().length()),
					String.valueOf(r.elapsed().toMillis())));
			if (!headersSaved && r.status() == 200) {
				SpikeFixtures.saveHeaders(FIXTURES, "datos-gob-es-page0.headers", r.status(), r.headers());
				for (String h : List.of("Content-Type", "Content-Length", "Last-Modified", "ETag", "Cache-Control", "Vary")) {
					metric(ID, "- " + h + ": " + r.header(h));
				}
				headersSaved = true;
			}
		}
		table(ID, List.of("_pageSize", "status", "content-type", "itemsPerPage", "items", "next", "bytes", "ms"), rows);
	}

	@Test
	@Order(2)
	void walkAllPages() {
		heading(ID, "Recorrido completo con `_pageSize=200&_page=N` explícitos");
		Response page0Small = api.get(DATOS_GOB + "?_pageSize=50&_page=0");
		probes.add(new Probe("GET página", page0Small.status(), page0Small.elapsed().toMillis(), page0Small.body().length()));
		SpikeFixtures.save(FIXTURES, "datos-gob-es-page0.json", page0Small.body());
		metric(ID, "`next` de la página 0 con _pageSize=50: " + text(page0Small.json().path("result"), "next")
				+ " (pierde el _pageSize: seguir `next` devuelve páginas de 10)");
		int pageSize = 200;
		String url = DATOS_GOB + "?_pageSize=" + pageSize + "&_page=0";
		var keys = new TreeMap<String, Integer>();
		var identifierPatterns = new TreeMap<String, Integer>();
		int withModified = 0;
		int distributions = 0;
		var modifiedSamples = new ArrayList<String>();
		while (url != null && pagesWalked < 50) {
			Response r = api.get(url);
			probes.add(new Probe("GET página", r.status(), r.elapsed().toMillis(), r.body().length()));
			assertThat(r.status()).as(url).isEqualTo(200);
			JsonNode root = r.json();
			JsonNode result = root.path("result");
			if (pagesWalked == 0) {
				metric(ID, "claves raíz=" + root.propertyNames() + " claves result=" + result.propertyNames()
						+ " format=" + text(root, "format") + " version=" + text(root, "version"));
				metric(ID, "primera página: " + r.summary());
			}
			for (JsonNode item : result.path("items")) {
				federated.add(item);
				item.propertyNames().forEach(k -> keys.merge(k, 1, Integer::sum));
				String identifier = text(item, "identifier");
				Matcher m = CATALOG_ID.matcher(identifier);
				identifierPatterns.merge(m.find() ? "…/catalogo/<id>" : identifier.replaceAll("\\d+", "N"), 1, Integer::sum);
				if (!text(item, "modified").isEmpty()) {
					withModified++;
					if (modifiedSamples.size() < 5) {
						modifiedSamples.add(text(item, "modified"));
					}
				}
				JsonNode dist = item.path("distribution");
				distributions += dist.isArray() ? dist.size() : dist.isObject() ? 1 : 0;
			}
			pagesWalked++;
			String next = text(result, "next");
			if (!result.path("items").isEmpty() && pagesWalked == 1) {
				metric(ID, "page=" + text(result, "page") + " startIndex=" + text(result, "startIndex") + " itemsPerPage="
						+ text(result, "itemsPerPage") + " next=" + next);
			}
			if (result.path("items").size() < pageSize) {
				SpikeFixtures.save(FIXTURES, "datos-gob-es-page-last.json", r.body());
				metric(ID, "última página: page=" + text(result, "page") + " items=" + result.path("items").size()
						+ " next=" + (next.isEmpty() ? "(ausente)" : next));
				url = null;
			}
			else {
				url = DATOS_GOB + "?_pageSize=" + pageSize + "&_page=" + pagesWalked;
			}
		}
		Response beyond = api.tryGet(DATOS_GOB + "?_pageSize=50&_page=99");
		probes.add(new Probe("GET página inexistente", beyond.status(), beyond.elapsed().toMillis(), beyond.body().length()));
		JsonNode beyondResult = beyond.status() == 200 && beyond.isJson() ? tryJson(beyond).path("result") : null;
		metric(ID, "página 99 (más allá del final): " + beyond.status() + " items="
				+ (beyondResult == null ? "-" : beyondResult.path("items").size()) + " next="
				+ (beyondResult == null ? "-" : (beyondResult.has("next") ? text(beyondResult, "next") : "(ausente)")));
		if (beyond.status() == 200) {
			SpikeFixtures.save(FIXTURES, "datos-gob-es-page-beyond.json", beyond.body());
		}
		metric(ID, "páginas=" + pagesWalked + " datasets federados=" + federated.size() + " con modified=" + withModified
				+ " distribuciones=" + distributions);
		counts(ID, "clave del item", keys);
		counts(ID, "forma de identifier", identifierPatterns);
		metric(ID, "muestras de modified: " + modifiedSamples);
		Response extended = api.tryGet(DATOS_GOB + "?_pageSize=1&_page=0&_metadata=all");
		probes.add(new Probe("GET _metadata=all", extended.status(), extended.elapsed().toMillis(), extended.body().length()));
		if (extended.status() == 200 && extended.isJson() && tryJson(extended) != null) {
			JsonNode first = tryJson(extended).path("result").path("items").path(0);
			metric(ID, "_metadata=all: claves del item=" + first.propertyNames() + " bytes=" + extended.body().length());
		}
		else {
			metric(ID, "_metadata=all -> " + extended.status());
		}
	}

	@Test
	@Order(3)
	void crossWithCatalog() {
		heading(ID, "Cruce con el catálogo municipal por `identifier`");
		var federatedIds = new TreeMap<Integer, JsonNode>();
		var duplicates = new ArrayList<Integer>();
		var unknown = new ArrayList<String>();
		for (JsonNode item : federated) {
			Matcher m = CATALOG_ID.matcher(text(item, "identifier"));
			if (!m.find()) {
				unknown.add(text(item, "identifier"));
				continue;
			}
			int id = Integer.parseInt(m.group(1));
			if (federatedIds.put(id, item) != null) {
				duplicates.add(id);
			}
		}
		var notInCatalog = new TreeSet<>(federatedIds.keySet());
		notInCatalog.removeAll(catalog.keySet());
		metric(ID, "federados con id municipal=" + federatedIds.size() + " duplicados=" + duplicates + " sin id municipal="
				+ unknown.size() + " " + unknown.stream().limit(5).toList());
		metric(ID, "federados que no están en el catálogo JSON=" + notInCatalog.size() + " " + notInCatalog);
		heading(ID, "Los federados sin ficha en el catálogo JSON: ¿existen en `catalogo/{id}.json`?");
		List<List<String>> probesRows = new ArrayList<>();
		var probedIds = new ArrayList<Integer>();
		var it = notInCatalog.iterator();
		for (int i = 0; it.hasNext(); i++) {
			int id = it.next();
			if (i < 3 || i >= notInCatalog.size() - 3 || i == notInCatalog.size() / 2) {
				probedIds.add(id);
			}
		}
		Pattern hierarchy = Pattern.compile("parent|part|serie|colecc|collect|padre|hijo|child|relat|dataset", Pattern.CASE_INSENSITIVE);
		boolean keysReported = false;
		for (int id : probedIds) {
			JsonNode item = federatedIds.get(id);
			Response r = api.tryGet(DATA_SPACE + "/catalogo/" + id + ".json");
			probes.add(new Probe("GET catalogo/{id}", r.status(), r.elapsed().toMillis(), r.body().length()));
			JsonNode d = r.status() == 200 && r.isJson() ? tryJson(r) : null;
			if (d != null) {
				if (!keysReported) {
					metric(ID, "claves del detalle de " + id + ": " + d.propertyNames());
					keysReported = true;
				}
				var related = new ArrayList<String>();
				d.properties().forEach(e -> {
					if (hierarchy.matcher(e.getKey()).find()) {
						String v = e.getValue().toString();
						related.add(e.getKey() + "=" + (v.length() > 160 ? v.substring(0, 160) + "…" : v));
					}
				});
				metric(ID, "- " + id + " campos de jerarquía o relación: " + related);
			}
			probesRows.add(List.of(String.valueOf(id), text(item.path("title").path(0), "_value"),
					text(item, "issued"), text(item, "modified"), String.valueOf(r.status()),
					d == null ? shortBody(r.body()) : text(d, "title"), d == null ? "-" : text(d, "abierto"),
					d == null ? "-" : text(d, "status"), d == null ? "-" : text(d, "modified")));
		}
		table(ID, List.of("id", "título en datos.gob.es", "issued (datos.gob.es)", "modified (datos.gob.es)", "status HTTP",
				"título municipal / cuerpo", "abierto", "status", "modified municipal"), probesRows);
		var notFederated = new TreeSet<>(catalog.keySet());
		notFederated.removeAll(federatedIds.keySet());
		metric(ID, "fichas del catálogo no federadas=" + notFederated.size() + " de " + catalog.size());
		Map<String, Integer> byOpen = new TreeMap<>();
		Map<String, Integer> byStatus = new TreeMap<>();
		Map<String, Integer> byOpenFederated = new TreeMap<>();
		for (int id : notFederated) {
			JsonNode d = catalog.get(id);
			byOpen.merge("abierto=" + text(d, "abierto").replace("", "").strip() + (text(d, "abierto").isBlank() ? "(vacío)" : ""), 1, Integer::sum);
			byStatus.merge("status=" + (text(d, "status").isBlank() ? "(vacío)" : text(d, "status")), 1, Integer::sum);
		}
		for (int id : federatedIds.keySet()) {
			JsonNode d = catalog.get(id);
			if (d != null) {
				byOpenFederated.merge("abierto=" + (text(d, "abierto").isBlank() ? "(vacío)" : text(d, "abierto")), 1, Integer::sum);
			}
		}
		counts(ID, "no federadas por abierto", byOpen);
		counts(ID, "no federadas por status", byStatus);
		counts(ID, "federadas por abierto", byOpenFederated);
		List<List<String>> sample = new ArrayList<>();
		for (int id : notFederated) {
			JsonNode d = catalog.get(id);
			if ("S".equals(text(d, "abierto")) && sample.size() < 15) {
				sample.add(List.of(String.valueOf(id), text(d, "title"), text(d, "status"), text(d, "modified"),
						d.path("explorable").asBoolean(false) ? "sí" : "no"));
			}
		}
		table(ID, List.of("id", "título", "status", "modified", "explorable"), sample);
		metric(ID, "(muestra de hasta 15 fichas abiertas no federadas)");

		heading(ID, "modified de datos.gob.es frente a modified del catálogo");
		int comparable = 0, same = 0, federatedNewer = 0, catalogNewer = 0, unparseable = 0;
		var examples = new ArrayList<String>();
		for (var e : federatedIds.entrySet()) {
			JsonNode d = catalog.get(e.getKey());
			String raw = text(e.getValue(), "modified");
			if (d == null || raw.isEmpty()) {
				continue;
			}
			LocalDate fed = parseSpanish(raw);
			LocalDateTime cat = SpikeJson.date(d.path("modified"));
			if (fed == null) {
				unparseable++;
				continue;
			}
			if (cat == null) {
				continue;
			}
			comparable++;
			long days = ChronoUnit.DAYS.between(cat.toLocalDate(), fed);
			if (Math.abs(days) <= 1) {
				same++;
			}
			else if (days > 0) {
				federatedNewer++;
				if (examples.size() < 4) {
					examples.add(e.getKey() + " «" + text(d, "title") + "» catálogo " + cat.toLocalDate() + " / datos.gob.es " + fed);
				}
			}
			else {
				catalogNewer++;
				if (examples.size() < 8) {
					examples.add(e.getKey() + " «" + text(d, "title") + "» catálogo " + cat.toLocalDate() + " / datos.gob.es " + fed);
				}
			}
		}
		metric(ID, "comparables=" + comparable + " iguales(±1 día)=" + same + " datos.gob.es más reciente=" + federatedNewer
				+ " catálogo más reciente=" + catalogNewer + " no parseables=" + unparseable);
		examples.forEach(x -> metric(ID, "- " + x));
	}

	@Test
	@Order(4)
	void conditionalRequests() {
		heading(ID, "Cabeceras condicionales");
		Response first = api.tryGet(DATOS_GOB + "?_pageSize=1&_page=0");
		probes.add(new Probe("GET _pageSize=1", first.status(), first.elapsed().toMillis(), first.body().length()));
		String etag = first.header("ETag");
		String lastModified = first.header("Last-Modified");
		metric(ID, "GET -> " + first.status() + " ETag=" + etag + " Last-Modified=" + lastModified);
		if (etag != null) {
			Response conditional = api.tryGet(DATOS_GOB + "?_pageSize=1&_page=0", h -> h.set("If-None-Match", etag));
			probes.add(new Probe("GET If-None-Match", conditional.status(), conditional.elapsed().toMillis(), conditional.body().length()));
			metric(ID, "If-None-Match -> " + conditional.status());
		}
		Response head = api.tryHead(DATOS_GOB + "?_pageSize=1&_page=0");
		probes.add(new Probe("HEAD", head.status(), head.elapsed().toMillis(), 0));
		metric(ID, "HEAD -> " + head.status() + " " + head.contentType());
	}

	@Test
	@Order(5)
	void cost() {
		heading(ID, "Coste");
		Map<String, List<Probe>> byWhat = new TreeMap<>();
		for (Probe p : probes) {
			byWhat.computeIfAbsent(p.what(), k -> new ArrayList<>()).add(p);
		}
		List<List<String>> rows = new ArrayList<>();
		byWhat.forEach((k, list) -> {
			List<Long> t = list.stream().map(Probe::ms).sorted().toList();
			rows.add(List.of(k, String.valueOf(list.size()),
					String.valueOf(list.stream().filter(p -> p.status() >= 200 && p.status() < 300).count()),
					String.valueOf(SpikeJson.percentile(t, 0.5)), String.valueOf(t.get(t.size() - 1)),
					String.valueOf(list.stream().mapToLong(Probe::bytes).sum())));
		});
		table(ID, List.of("petición", "n", "2xx", "p50 ms", "max ms", "bytes"), rows);
		metric(ID, "peticiones=" + probes.size() + " tiempo total=" + Duration.ofMillis(probes.stream().mapToLong(Probe::ms).sum()));
	}

	// --- helpers --------------------------------------------------------------------------------------------------

	static LocalDate parseSpanish(String raw) {
		Matcher m = SPANISH_DATE.matcher(raw.strip());
		if (!m.matches()) {
			return null;
		}
		Integer month = MONTHS.get(m.group(2).toLowerCase());
		if (month == null) {
			return null;
		}
		int sign = m.group(7).equals("-") ? -1 : 1;
		ZoneOffset offset = ZoneOffset.ofHoursMinutes(sign * Integer.parseInt(m.group(8)), sign * Integer.parseInt(m.group(9)));
		OffsetDateTime at = OffsetDateTime.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)),
				Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), 0, offset);
		// datos.gob.es publica medianoche local de Madrid convertida a UTC (22:00 o 23:00 del día anterior)
		return at.atZoneSameInstant(java.time.ZoneId.of("Europe/Madrid")).toLocalDate();
	}

	static String shortBody(String body) {
		String s = body.strip().replace("\n", " ").replace("|", "/");
		return s.length() > 80 ? s.substring(0, 80) + "…" : s;
	}

	static JsonNode tryJson(Response r) {
		try {
			return r.json();
		}
		catch (RuntimeException e) {
			return null;
		}
	}

}
