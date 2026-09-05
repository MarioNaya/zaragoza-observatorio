package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.DATA_SPACE;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;

/**
 * S0.1 — Catálogo de datasets (SPEC.md §3, Fase 0). Informe: docs/spikes/S0.1-catalogo.md.
 * <p>
 * Preguntas: estructura del catálogo real, campos de fecha disponibles y su cobertura, frecuencia declarada,
 * cuántos datasets exponen endpoint consultable (y si casan con el Swagger), límites de paginación.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S01CatalogSpike {

	static final String ID = "S0.1-catalogo";
	static final String CATALOG = DATA_SPACE + "/catalogo.json";
	static final String SWAGGER = SEDE + "/catalogo/api.json";
	static final Pattern ISO_PERIOD = Pattern.compile("^P.*");

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient();
	static List<JsonNode> datasets;
	static int totalCount;

	@BeforeAll
	static void fetchCatalog() {
		SpikeFixtures.startMetrics(ID);
		heading(ID, "Descarga del catálogo");
		var response = api.get(CATALOG + "?rows=500&start=0");
		metric(ID, response.summary());
		assertThat(response.status()).isEqualTo(200);
		SpikeFixtures.save("catalog", "catalogo-rows500-start0.json", response.body());
		JsonNode root = response.json();
		totalCount = root.path("totalCount").asInt();
		datasets = root.path("result").valueStream().toList();
		metric(ID, "totalCount=" + totalCount + " devueltos=" + datasets.size() + " start=" + root.path("start")
				+ " rows=" + root.path("rows"));
		assertThat(datasets).isNotEmpty();
	}

	@Test
	@Order(1)
	void pagingBehaviour() {
		heading(ID, "Paginación del catálogo");
		for (String query : List.of("", "?rows=10", "?rows=1000", "?rows=100&start=400", "?rows=100&start=436",
				"?rows=5&sort=modified%20desc", "?rows=5&sort=modified%20asc", "?rows=3&fl=id,title,modified")) {
			var r = api.get(CATALOG + query);
			String returned = r.isJson() && r.status() == 200 ? String.valueOf(r.json().path("result").size()) : "-";
			metric(ID, "`" + (query.isEmpty() ? "(sin parámetros)" : query) + "` -> " + r.status() + " rows="
					+ (r.isJson() && r.status() == 200 ? r.json().path("rows").toString() : "-") + " devueltos="
					+ returned + " ms=" + r.elapsed().toMillis());
			if (query.startsWith("?rows=5&sort")) {
				metric(ID, "   primeros modified: " + r.json().path("result").valueStream()
						.map(n -> n.path("modified").asString("")).toList());
			}
		}
	}

	@Test
	@Order(2)
	void fieldCoverage() {
		heading(ID, "Cobertura de campos (no vacíos sobre " + datasets.size() + ")");
		Map<String, Integer> filled = new TreeMap<>();
		Map<String, String> sampleType = new TreeMap<>();
		for (JsonNode d : datasets) {
			d.properties().forEach(e -> {
				String key = e.getKey();
				JsonNode v = e.getValue();
				sampleType.putIfAbsent(key, v.getNodeType().name());
				if (!isEmpty(v)) {
					filled.merge(key, 1, Integer::sum);
				}
				else {
					filled.putIfAbsent(key, 0);
				}
			});
		}
		List<List<String>> rows = filled.entrySet().stream()
				.map(e -> List.of(e.getKey(), sampleType.get(e.getKey()), String.valueOf(e.getValue())))
				.toList();
		table(ID, List.of("campo", "tipo", "no vacíos"), rows);
	}

	@Test
	@Order(3)
	void dateDistributions() {
		heading(ID, "Distribución de fechas por año");
		for (String field : List.of("issued", "modified", "lastUpdated")) {
			Map<String, Integer> byYear = new TreeMap<>();
			for (JsonNode d : datasets) {
				LocalDateTime t = date(d.path(field));
				byYear.merge(t == null ? "(vacío)" : String.valueOf(t.getYear()), 1, Integer::sum);
			}
			metric(ID, "**" + field + "**: " + byYear);
		}
		heading(ID, "Relación lastUpdated vs modified");
		int same = 0, lastUpdatedAfter = 0, modifiedAfter = 0, missing = 0;
		List<Long> gapDays = new ArrayList<>();
		for (JsonNode d : datasets) {
			LocalDateTime m = date(d.path("modified"));
			LocalDateTime lu = date(d.path("lastUpdated"));
			if (m == null || lu == null) {
				missing++;
				continue;
			}
			long gap = ChronoUnit.DAYS.between(m, lu);
			gapDays.add(gap);
			if (gap == 0) {
				same++;
			}
			else if (gap > 0) {
				lastUpdatedAfter++;
			}
			else {
				modifiedAfter++;
			}
		}
		gapDays.sort(Long::compare);
		metric(ID, "mismo día=" + same + " lastUpdated>modified=" + lastUpdatedAfter + " modified>lastUpdated="
				+ modifiedAfter + " alguno vacío=" + missing);
		if (!gapDays.isEmpty()) {
			metric(ID, "gap días (lastUpdated - modified): min=" + gapDays.get(0) + " p50="
					+ gapDays.get(gapDays.size() / 2) + " p90=" + gapDays.get((int) (gapDays.size() * 0.9))
					+ " max=" + gapDays.get(gapDays.size() - 1));
		}
		heading(ID, "Ejemplos con lastUpdated muy posterior a modified (¿metadato vs dato?)");
		datasets.stream()
				.filter(d -> date(d.path("modified")) != null && date(d.path("lastUpdated")) != null)
				.sorted((a, b) -> Long.compare(gap(b), gap(a)))
				.limit(8)
				.forEach(d -> metric(ID, "- id=" + d.path("id") + " modified=" + d.path("modified").asString("")
						+ " lastUpdated=" + d.path("lastUpdated").asString("") + " periodicidad="
						+ d.path("accrualPeriodicity").asString("") + " «" + d.path("title").asString("") + "»"));
	}

	@Test
	@Order(4)
	void freshnessAgainstDeclaredPeriodicity() {
		heading(ID, "accrualPeriodicity declarada");
		Map<String, Integer> periodicity = new LinkedHashMap<>();
		for (JsonNode d : datasets) {
			periodicity.merge(d.path("accrualPeriodicity").asString("(vacío)"), 1, Integer::sum);
		}
		counts(ID, "accrualPeriodicity", periodicity);

		heading(ID, "Antigüedad de `modified` relativa al periodo declarado (hoy=" + LocalDate.now() + ")");
		metric(ID, "ratio = días desde modified / días del periodo. Buckets: <=1 (al día), <=2, <=5, >5, sin periodo parseable.");
		Map<String, int[]> buckets = new TreeMap<>();
		for (JsonNode d : datasets) {
			String p = d.path("accrualPeriodicity").asString("(vacío)");
			LocalDateTime m = date(d.path("modified"));
			int[] b = buckets.computeIfAbsent(p, k -> new int[6]);
			Long periodDays = periodDays(p);
			if (m == null) {
				b[5]++;
				continue;
			}
			if (periodDays == null || periodDays == 0) {
				b[4]++;
				continue;
			}
			double ratio = (double) ChronoUnit.DAYS.between(m, LocalDateTime.now()) / periodDays;
			if (ratio <= 1) {
				b[0]++;
			}
			else if (ratio <= 2) {
				b[1]++;
			}
			else if (ratio <= 5) {
				b[2]++;
			}
			else {
				b[3]++;
			}
		}
		List<List<String>> rows = new ArrayList<>();
		buckets.forEach((p, b) -> rows.add(List.of(p, String.valueOf(periodDays(p)), String.valueOf(b[0]),
				String.valueOf(b[1]), String.valueOf(b[2]), String.valueOf(b[3]), String.valueOf(b[4]),
				String.valueOf(b[5]))));
		table(ID, List.of("periodicidad", "días", "<=1", "<=2", "<=5", ">5", "no parseable", "sin modified"), rows);

		heading(ID, "status y geo");
		Map<String, Integer> status = new LinkedHashMap<>();
		Map<String, Integer> geo = new LinkedHashMap<>();
		for (JsonNode d : datasets) {
			status.merge(d.path("status").asString("(vacío)"), 1, Integer::sum);
			geo.merge(d.path("geo").asString("(vacío)"), 1, Integer::sum);
		}
		counts(ID, "status", status);
		counts(ID, "geo", geo);
	}

	@Test
	@Order(5)
	void apiBackedDatasetsMatchSwaggerTags() {
		heading(ID, "Datasets con distribución application/api frente al Swagger");
		var swagger = api.get(SWAGGER);
		metric(ID, swagger.summary());
		SpikeFixtures.save("catalog", "swagger-api.json", swagger.body());
		JsonNode paths = swagger.json().path("paths");
		var tags = new TreeSet<String>();
		paths.properties().forEach(p -> p.getValue().properties()
				.forEach(op -> op.getValue().path("tags").valueStream().forEach(t -> tags.add(t.asString()))));
		metric(ID, "swagger paths=" + paths.size() + " tags=" + tags.size());

		Map<String, Integer> mediaTypes = new LinkedHashMap<>();
		int apiDatasets = 0, matched = 0;
		List<String> unmatched = new ArrayList<>();
		var usedTags = new TreeSet<String>();
		for (JsonNode d : datasets) {
			boolean hasApi = false;
			for (JsonNode f : d.path("formato")) {
				String mt = f.path("mediaType").asString("(vacío)");
				mediaTypes.merge(mt, 1, Integer::sum);
				if ("application/api".equals(mt)) {
					hasApi = true;
					String tag = tagFromDocsUrl(f.path("accessURL").asString(""));
					if (tag != null && tags.contains(tag)) {
						matched++;
						usedTags.add(tag);
					}
					else {
						unmatched.add("id=" + d.path("id") + " accessURL=" + f.path("accessURL").asString(""));
					}
				}
			}
			if (hasApi) {
				apiDatasets++;
			}
		}
		counts(ID, "formato.mediaType", mediaTypes);
		metric(ID, "datasets con application/api=" + apiDatasets + " distribuciones api con tag en Swagger=" + matched
				+ " sin correspondencia=" + unmatched.size() + " tags distintos usados=" + usedTags.size() + "/"
				+ tags.size());
		unmatched.stream().limit(15).forEach(u -> metric(ID, "- sin tag: " + u));
		var unusedTags = new TreeSet<>(tags);
		unusedTags.removeAll(usedTags);
		metric(ID, "tags del Swagger sin dataset asociado (" + unusedTags.size() + "): " + unusedTags);
	}

	@Test
	@Order(6)
	void detailEndpoint() {
		heading(ID, "Detalle de dataset `/catalogo/{id}.json`");
		for (JsonNode d : List.of(datasets.get(0), datasets.get(datasets.size() / 2),
				datasets.get(datasets.size() - 1))) {
			int id = d.path("id").asInt();
			var r = api.get(DATA_SPACE + "/catalogo/" + id + ".json");
			metric(ID, r.summary());
			if (r.status() == 200 && r.isJson()) {
				SpikeFixtures.save("catalog", "catalogo-" + id + ".json", r.body());
				var detailKeys = new TreeSet<>(r.json().propertyNames());
				var listKeys = new TreeSet<>(d.propertyNames());
				detailKeys.removeAll(listKeys);
				metric(ID, "id=" + id + " campos solo en detalle: " + detailKeys + " recursos="
						+ r.json().path("recurso").size() + " formatos=" + r.json().path("formato").size());
			}
		}
	}

	@Test
	@Order(7)
	void dcatAndDatosGobEs() {
		heading(ID, "DCAT RDF del catálogo");
		var rdf = api.get(DATA_SPACE + "/catalogo.rdf");
		metric(ID, rdf.summary());
		metric(ID, "ocurrencias de <dcat:Dataset=" + count(rdf.body(), "<dcat:Dataset") + " dcat:Distribution="
				+ count(rdf.body(), "<dcat:Distribution") + " dct:modified=" + count(rdf.body(), "dct:modified")
				+ " dct:accrualPeriodicity=" + count(rdf.body(), "dct:accrualPeriodicity"));

		heading(ID, "datos.gob.es (publisher L01502973) como contraste");
		int page = 0, items = 0;
		String keys = "";
		while (page < 15) {
			var r = api.get("https://datos.gob.es/apidata/catalog/dataset/publisher/L01502973.json?_pageSize=50&_page="
					+ page);
			if (r.status() != 200) {
				metric(ID, "page=" + page + " -> " + r.status());
				break;
			}
			if (page == 0) {
				SpikeFixtures.save("catalog", "datos-gob-es-page0.json", r.body());
				JsonNode first = r.json().path("result").path("items").path(0);
				keys = String.valueOf(new TreeSet<>(first.propertyNames()));
			}
			int n = r.json().path("result").path("items").size();
			items += n;
			if (n < 50) {
				break;
			}
			page++;
		}
		metric(ID, "datos.gob.es datasets=" + items + " (páginas leídas=" + (page + 1) + ") campos del item: " + keys);
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	static boolean isEmpty(JsonNode v) {
		return v == null || v.isNull() || v.isMissingNode() || (v.isString() && v.asString().isBlank())
				|| ((v.isArray() || v.isObject()) && v.size() == 0);
	}

	static LocalDateTime date(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		String s = node.asString("").trim();
		if (s.isEmpty()) {
			return null;
		}
		try {
			return LocalDateTime.parse(s);
		}
		catch (DateTimeParseException e) {
			try {
				return OffsetDateTime.parse(s).toLocalDateTime();
			}
			catch (DateTimeParseException e2) {
				try {
					return LocalDate.parse(s.substring(0, 10)).atStartOfDay();
				}
				catch (RuntimeException e3) {
					return null;
				}
			}
		}
	}

	static long gap(JsonNode d) {
		return ChronoUnit.DAYS.between(date(d.path("modified")), date(d.path("lastUpdated")));
	}

	/** Días aproximados de un periodo ISO 8601 (P1Y, P3M, P1W, P1D, P0DT1S). null si no es periodo. */
	static Long periodDays(String p) {
		if (p == null || !ISO_PERIOD.matcher(p).matches()) {
			return null;
		}
		try {
			Period period = Period.parse(p);
			return period.getYears() * 365L + period.getMonths() * 30L + period.getDays();
		}
		catch (DateTimeParseException e) {
			try {
				return Duration.parse(p).toDays();
			}
			catch (DateTimeParseException e2) {
				return null;
			}
		}
	}

	static String tagFromDocsUrl(String accessUrl) {
		int hash = accessUrl.indexOf("#/");
		if (hash < 0) {
			return null;
		}
		return URLDecoder.decode(accessUrl.substring(hash + 2), StandardCharsets.UTF_8).trim();
	}

	static int count(String haystack, String needle) {
		int n = 0, i = 0;
		while ((i = haystack.indexOf(needle, i)) >= 0) {
			n++;
			i += needle.length();
		}
		return n;
	}

}
