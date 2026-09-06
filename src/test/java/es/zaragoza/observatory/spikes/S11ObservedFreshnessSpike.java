package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.DATA_SPACE;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.enc;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

import es.zaragoza.observatory.catalog.domain.Periodicity;
import es.zaragoza.observatory.spikes.support.SpikeFixtures;
import es.zaragoza.observatory.spikes.support.SpikeJson;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S1.1 — Frescura observada (SPEC.md §4.6, docs/ESTADO.md §4). Informe: docs/spikes/S1.1-frescura-observada.md.
 * <p>
 * Pregunta: qué devuelven las distribuciones de los datasets del catálogo cuando se les pregunta por su estado
 * real, y a qué coste: (a) ficheros descargables → cabeceras {@code HEAD}; (b) endpoints de la API de la sede →
 * campos de fecha, {@code sort} y {@code totalCount} con {@code rows=1}; (c) WFS → {@code GetCapabilities} y
 * {@code GetFeature&resultType=hits}; (d) el resto (SPARQL, buscadores, RSS/Atom, HTML) → si responden y con qué.
 * El objetivo es elegir el método de observación por tipo de distribución para el 60 % de fichas no evaluables
 * por periodicidad declarada (S0.1).
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S11ObservedFreshnessSpike {

	static final String ID = "S1.1-frescura-observada";
	static final String FIXTURES = "catalog/observation";
	static final String HOST = "https://www.zaragoza.es";
	static final int SAMPLE_PER_TYPE = 10;
	static final Set<String> CONTACT_FIELDS = Set.of("email", "telefonos", "telefono", "fax", "comment");
	static final Pattern DATE_FIELD = Pattern.compile(
			"fecha|date|modified|updated|timestamp|creation|inicio|pubdate|anyo|year|datetime",
			Pattern.CASE_INSENSITIVE);
	static final Pattern FILE_EXTENSION = Pattern.compile(
			"\\.(xlsx?|ods|csv|zip|pdf|gml|xml|kml|kmz|json|geojson|txt|rar|7z|shp|rdf|n3|ttl|dxf|dwg)$",
			Pattern.CASE_INSENSITIVE);
	static final Pattern NUMBER_MATCHED = Pattern.compile("numberMatched=\"(\\d+|unknown)\"");
	static final DateTimeFormatter ZONE_NAME = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz",
			Locale.ENGLISH);

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(90));
	static List<JsonNode> catalog = new ArrayList<>();
	static List<Dist> dists = new ArrayList<>();
	static Set<Integer> notEvaluable = new HashSet<>();
	static List<Probe> probes = new ArrayList<>();
	static int savedApiFixtures = 0;

	enum Kind {
		API, WFS, WMS, FILE, OTHER
	}

	/** Una distribución de {@code formato[]} con lo que necesitamos de su ficha. */
	record Dist(int datasetId, String title, String periodicity, LocalDateTime declaredModified, boolean geo,
			int distId, String mediaType, String accessUrl, String downloadUrl, LocalDateTime distModified,
			String wfsFeatureName, String apiDefinition) {

		String url() {
			return downloadUrl.isEmpty() ? accessUrl : downloadUrl;
		}

		String path() {
			String u = url();
			int q = u.indexOf('?');
			return q < 0 ? u : u.substring(0, q);
		}

		boolean isSedeApiUrl() {
			return url().contains("/sede/servicio/") || url().contains("/api/recurso/");
		}

		Kind kind() {
			String mt = mediaType.toLowerCase(Locale.ROOT);
			if (url().isEmpty()) {
				return Kind.OTHER;
			}
			if ("application/api".equals(mt)) {
				return Kind.API;
			}
			if (mt.contains("wfs")) {
				return Kind.WFS;
			}
			if (mt.contains("wms")) {
				return Kind.WMS;
			}
			if (isSedeApiUrl() && (mt.contains("json") || mt.contains("csv") || mt.contains("xml"))) {
				return Kind.API;
			}
			if (!isSedeApiUrl() && FILE_EXTENSION.matcher(path()).find()) {
				return Kind.FILE;
			}
			return Kind.OTHER;
		}

		String extension() {
			Matcher m = FILE_EXTENSION.matcher(path());
			if (!m.find()) {
				return "-";
			}
			String ext = m.group(1).toLowerCase(Locale.ROOT);
			return ext.equals("xlsx") ? "xls" : ext;
		}

		boolean ne() {
			return notEvaluable.contains(datasetId);
		}

		String label() {
			return datasetId + " «" + (title.length() > 40 ? title.substring(0, 40) + "…" : title) + "»";
		}

		String declaredModifiedDate() {
			return declaredModified == null ? "-" : declaredModified.toLocalDate().toString();
		}
	}

	/** Coste de cada petición, para la proyección final. */
	record Probe(Kind kind, String what, int status, long ms, long bytes) {
	}

	@BeforeAll
	static void loadCatalog() {
		SpikeFixtures.startMetrics(ID);
		heading(ID, "Catálogo (fl reducido)");
		var r = api.get(DATA_SPACE
				+ "/catalogo.json?rows=500&start=0&fl=id,title,modified,accrualPeriodicity,geo,formato");
		metric(ID, r.summary());
		assertThat(r.status()).isEqualTo(200);
		r.json().path("result").forEach(catalog::add);
		for (JsonNode d : catalog) {
			int id = d.path("id").asInt();
			String periodicity = text(d, "accrualPeriodicity");
			LocalDateTime modified = SpikeJson.date(d.path("modified"));
			if (Periodicity.days(periodicity) == null || modified == null) {
				notEvaluable.add(id);
			}
			for (JsonNode f : d.path("formato")) {
				dists.add(new Dist(id, text(d, "title"), periodicity, modified, "S".equals(text(d, "geo")),
						f.path("id").asInt(0), text(f, "mediaType"), clean(text(f, "accessURL")),
						clean(text(f, "downloadURL")), SpikeJson.date(f.path("modified")), text(f, "wfsFeatureName"),
						text(f, "apiDefinition")));
			}
		}
		metric(ID, "datasets=" + catalog.size() + " no evaluables por periodicidad declarada=" + notEvaluable.size()
				+ " distribuciones=" + dists.size());

		heading(ID, "Distribuciones por tipo de observación (todas / de fichas no evaluables)");
		Map<String, int[]> byKind = new TreeMap<>();
		for (Dist d : dists) {
			int[] c = byKind.computeIfAbsent(d.kind() + (d.kind() == Kind.FILE ? " ." + d.extension() : ""),
					k -> new int[2]);
			c[0]++;
			if (d.ne()) {
				c[1]++;
			}
		}
		List<List<String>> rows = new ArrayList<>();
		byKind.forEach((k, c) -> rows.add(List.of(k, String.valueOf(c[0]), String.valueOf(c[1]))));
		table(ID, List.of("tipo", "distribuciones", "de fichas no evaluables"), rows);

		heading(ID, "Fichas por mejor tipo disponible (API > WFS > FILE > WMS > OTHER > ninguna)");
		Map<String, Integer> bestAll = new TreeMap<>();
		Map<String, Integer> bestNe = new TreeMap<>();
		for (JsonNode d : catalog) {
			int id = d.path("id").asInt();
			Kind best = bestKind(id);
			String key = best == null ? "(sin distribuciones)" : best.name();
			bestAll.merge(key, 1, Integer::sum);
			if (notEvaluable.contains(id)) {
				bestNe.merge(key, 1, Integer::sum);
			}
		}
		List<List<String>> bestRows = new ArrayList<>();
		bestAll.forEach((k, n) -> bestRows.add(List.of(k, String.valueOf(n), String.valueOf(bestNe.getOrDefault(k, 0)))));
		table(ID, List.of("mejor tipo", "fichas", "de ellas no evaluables"), bestRows);
	}

	static Kind bestKind(int datasetId) {
		Kind best = null;
		for (Dist dist : dists) {
			if (dist.datasetId() == datasetId && (best == null || rank(dist.kind()) < rank(best))) {
				best = dist.kind();
			}
		}
		return best;
	}

	static int rank(Kind k) {
		return switch (k) {
			case API -> 0;
			case WFS -> 1;
			case FILE -> 2;
			case WMS -> 3;
			case OTHER -> 4;
		};
	}

	@Test
	@Order(1)
	void fileDistributionsAnswerHead() {
		heading(ID, "Ficheros descargables: HEAD (muestra de " + SAMPLE_PER_TYPE
				+ " por extensión, fichas no evaluables primero)");
		Map<String, List<Dist>> byExt = new TreeMap<>();
		for (Dist d : dists) {
			if (d.kind() == Kind.FILE) {
				byExt.computeIfAbsent(d.extension(), k -> new ArrayList<>()).add(d);
			}
		}
		List<List<String>> rows = new ArrayList<>();
		Map<String, int[]> summary = new TreeMap<>();
		Map<String, List<Long>> timings = new TreeMap<>();
		int newerThanDeclared = 0, olderThanDeclared = 0, comparable = 0;
		for (var entry : byExt.entrySet()) {
			String ext = entry.getKey();
			int saved = 0;
			for (Dist d : sample(entry.getValue(), SAMPLE_PER_TYPE)) {
				Response r = api.tryHead(d.url());
				String method = "HEAD";
				if (r.status() == 405 || r.status() == 403 || r.status() == 501 || r.status() == 400) {
					r = api.tryGet(d.url(), h -> h.set("Range", "bytes=0-0"));
					method = "GET Range";
				}
				probes.add(new Probe(Kind.FILE, ext, r.status(), r.elapsed().toMillis(), r.body().length()));
				String lastModified = r.header("Last-Modified");
				ZonedDateTime parsed = parseLastModified(lastModified);
				String format = lastModified == null ? "-"
						: parsed == null ? "no parseable" : lastModified.endsWith("GMT") ? "RFC 1123" : "zona nombrada";
				int[] s = summary.computeIfAbsent(ext, k -> new int[6]);
				s[0]++;
				if (r.status() == 200 || r.status() == 206) {
					s[1]++;
				}
				if (lastModified != null) {
					s[2]++;
				}
				if (parsed != null) {
					s[3]++;
				}
				if (r.header("ETag") != null) {
					s[4]++;
				}
				if (r.contentLength() >= 0 || r.header("Content-Range") != null) {
					s[5]++;
				}
				timings.computeIfAbsent(ext, k -> new ArrayList<>()).add(r.elapsed().toMillis());
				String comparison = "-";
				if (parsed != null && d.declaredModified() != null) {
					comparable++;
					long gap = ChronoUnit.DAYS.between(d.declaredModified().toLocalDate(), parsed.toLocalDate());
					if (gap > 1) {
						newerThanDeclared++;
						comparison = "fichero " + gap + " d más nuevo que modified";
					}
					else if (gap < -1) {
						olderThanDeclared++;
						comparison = "fichero " + (-gap) + " d más viejo que modified";
					}
					else {
						comparison = "≈ modified";
					}
				}
				rows.add(List.of(ext, d.label(), method, String.valueOf(r.status()), r.contentType(),
						r.contentLength() < 0 ? "-" : String.valueOf(r.contentLength()),
						lastModified == null ? "-" : lastModified, format, r.header("ETag") == null ? "-" : "sí",
						String.valueOf(r.elapsed().toMillis()), d.declaredModifiedDate(), comparison,
						r.failed() ? r.body() : shortUrl(d.url())));
				if ((r.status() == 200 || r.status() == 206) && saved < 2) {
					SpikeFixtures.saveHeaders(FIXTURES,
							"head-" + ext + "-" + d.datasetId() + "-" + d.distId() + ".headers", r.status(), r.headers());
					saved++;
				}
			}
		}
		table(ID, List.of("ext", "dataset", "método", "status", "content-type", "content-length", "Last-Modified",
				"formato LM", "ETag", "ms", "modified declarado", "comparación", "url / error"), rows);

		heading(ID, "Ficheros: resumen por extensión");
		List<List<String>> sum = new ArrayList<>();
		summary.forEach((ext, s) -> {
			List<Long> t = timings.get(ext);
			t.sort(Long::compare);
			sum.add(List.of(ext, String.valueOf(s[0]), String.valueOf(s[1]), String.valueOf(s[2]),
					String.valueOf(s[3]), String.valueOf(s[4]), String.valueOf(s[5]),
					String.valueOf(SpikeJson.percentile(t, 0.5)), String.valueOf(t.get(t.size() - 1))));
		});
		table(ID, List.of("ext", "n", "200/206", "Last-Modified", "parseable", "ETag", "Content-Length", "p50 ms",
				"max ms"), sum);
		metric(ID, "comparables (Last-Modified y modified declarado)=" + comparable
				+ " fichero más nuevo que modified=" + newerThanDeclared + " fichero más viejo=" + olderThanDeclared);
	}

	@Test
	@Order(2)
	void apiDistributionsExposeDatesAndCounts() {
		heading(ID, "Distribuciones application/api: rows=1, forma, totalCount, campos de fecha y sort (todas)");
		List<Dist> apis = dists.stream().filter(d -> "application/api".equals(d.mediaType())).toList();
		List<List<String>> rows = new ArrayList<>();
		int skipped = 0;
		for (Dist d : apis) {
			if (d.downloadUrl().isEmpty()) {
				skipped++;
				metric(ID, "- sin downloadURL: " + d.label() + " accessURL=" + d.accessUrl());
				continue;
			}
			rows.add(probeEndpoint(d, apiUrl(d.downloadUrl(), d.geo())));
		}
		table(ID, endpointHeader(), rows);
		metric(ID, "distribuciones api=" + apis.size() + " sondeadas=" + rows.size() + " sin downloadURL=" + skipped);
		summarizeEndpoints("application/api", rows);
	}

	@Test
	@Order(3)
	void jsonDistributionsWithoutApiBehaveLikeEndpoints() {
		heading(ID, "Distribuciones application/json de la sede en fichas SIN application/api (muestra)");
		Set<Integer> withApi = new HashSet<>();
		dists.stream().filter(d -> "application/api".equals(d.mediaType())).forEach(d -> withApi.add(d.datasetId()));
		List<Dist> jsons = dists.stream()
				.filter(d -> d.kind() == Kind.API && !withApi.contains(d.datasetId()))
				.filter(d -> d.mediaType().contains("json"))
				.filter(d -> d.path().endsWith(".json") || d.path().endsWith(".geojson"))
				.toList();
		metric(ID, "candidatas=" + jsons.size() + " en " + jsons.stream().map(Dist::datasetId).distinct().count()
				+ " fichas; muestra de hasta 25 (no evaluables primero)");
		List<List<String>> rows = new ArrayList<>();
		for (Dist d : sample(jsons, 25)) {
			rows.add(probeEndpoint(d, apiUrl(d.url(), d.geo())));
		}
		table(ID, endpointHeader(), rows);
		summarizeEndpoints("application/json (sede)", rows);
	}

	@Test
	@Order(4)
	void wfsCapabilitiesAndHits() {
		heading(ID, "WFS: GetCapabilities por servicio");
		Map<String, List<Dist>> byBase = new TreeMap<>();
		for (Dist d : dists) {
			if (d.kind() == Kind.WFS) {
				byBase.computeIfAbsent(d.path(), k -> new ArrayList<>()).add(d);
			}
		}
		List<List<String>> rows = new ArrayList<>();
		boolean savedCapabilities = false;
		for (var entry : byBase.entrySet()) {
			String base = entry.getKey();
			Response r = api.tryGet(base + "?service=WFS&version=2.0.0&request=GetCapabilities");
			probes.add(new Probe(Kind.WFS, "GetCapabilities", r.status(), r.elapsed().toMillis(), r.body().length()));
			int featureTypes = count(r.body(), "<FeatureType>") + count(r.body(), "<wfs:FeatureType>");
			long ne = entry.getValue().stream().filter(Dist::ne).count();
			rows.add(List.of(shortUrl(base), String.valueOf(entry.getValue().size()), String.valueOf(ne),
					String.valueOf(r.status()), r.contentType(), String.valueOf(r.body().length()),
					String.valueOf(featureTypes), r.header("Last-Modified") == null ? "-" : r.header("Last-Modified"),
					r.header("ETag") == null ? "-" : "sí", String.valueOf(r.elapsed().toMillis()),
					r.failed() ? r.body() : ""));
			if (!savedCapabilities && r.status() == 200 && r.body().length() < 400_000 && featureTypes > 0) {
				SpikeFixtures.save(FIXTURES, "wfs-getcapabilities-" + slug(base) + ".xml", r.body());
				savedCapabilities = true;
			}
		}
		table(ID, List.of("servicio", "distribuciones", "de no evaluables", "status", "content-type", "bytes",
				"FeatureType", "Last-Modified", "ETag", "ms", "error"), rows);

		heading(ID, "WFS: GetFeature resultType=hits y count=1 por capa (muestra)");
		List<Dist> layers = dists.stream().filter(d -> d.kind() == Kind.WFS && !d.wfsFeatureName().isEmpty()).toList();
		metric(ID, "distribuciones WFS con wfsFeatureName=" + layers.size() + "/"
				+ dists.stream().filter(d -> d.kind() == Kind.WFS).count());
		List<List<String>> layerRows = new ArrayList<>();
		int saved = 0;
		for (Dist d : sample(layers, 12)) {
			String base = d.path();
			String common = "?service=WFS&version=2.0.0&request=GetFeature&typeNames=" + enc(d.wfsFeatureName());
			Response hits = api.tryGet(base + common + "&resultType=hits");
			probes.add(new Probe(Kind.WFS, "hits", hits.status(), hits.elapsed().toMillis(), hits.body().length()));
			Matcher m = NUMBER_MATCHED.matcher(hits.body());
			String matched = m.find() ? m.group(1) : "-";
			Response one = api.tryGet(base + common + "&count=1&outputFormat=application/json");
			probes.add(new Probe(Kind.WFS, "count=1", one.status(), one.elapsed().toMillis(), one.body().length()));
			String dateProps = "-";
			String props = "-";
			if (one.status() == 200 && one.isJson() && tryJson(one) != null) {
				JsonNode feature = tryJson(one).path("features").path(0);
				var names = new TreeSet<>(feature.path("properties").propertyNames());
				props = String.valueOf(names.size());
				dateProps = names.stream().filter(n -> DATE_FIELD.matcher(n).find())
						.map(n -> n + "=" + text(feature.path("properties"), n)).toList().toString();
				if (saved < 2) {
					SpikeFixtures.save(FIXTURES, "wfs-hits-" + slug(d.wfsFeatureName()) + ".xml", hits.body());
					SpikeFixtures.save(FIXTURES, "wfs-count1-" + slug(d.wfsFeatureName()) + ".json", one.body());
					saved++;
				}
			}
			layerRows.add(List.of(d.label(), d.wfsFeatureName(), String.valueOf(hits.status()), matched,
					String.valueOf(hits.elapsed().toMillis()), String.valueOf(one.status()), one.contentType(),
					String.valueOf(one.body().length()), String.valueOf(one.elapsed().toMillis()), props, dateProps,
					d.declaredModifiedDate(), hits.failed() ? hits.body() : one.failed() ? one.body() : ""));
		}
		table(ID, List.of("dataset", "capa", "hits status", "numberMatched", "hits ms", "count=1 status",
				"content-type", "bytes", "count=1 ms", "propiedades", "propiedades con fecha", "modified declarado",
				"error"), layerRows);

		heading(ID, "WMS: GetCapabilities (2 servicios, solo disponibilidad)");
		Map<String, Long> wmsBases = new TreeMap<>();
		dists.stream().filter(d -> d.kind() == Kind.WMS).forEach(d -> wmsBases.merge(d.path(), 1L, Long::sum));
		int n = 0;
		for (var entry : wmsBases.entrySet()) {
			if (n++ >= 2) {
				break;
			}
			Response r = api.tryGet(entry.getKey() + "?service=WMS&version=1.3.0&request=GetCapabilities");
			probes.add(new Probe(Kind.WMS, "GetCapabilities", r.status(), r.elapsed().toMillis(), r.body().length()));
			metric(ID, "- " + shortUrl(entry.getKey()) + " (" + entry.getValue() + " distribuciones) -> " + r.status()
					+ " " + r.contentType() + " bytes=" + r.body().length() + " Layer=" + count(r.body(), "<Layer")
					+ " ms=" + r.elapsed().toMillis());
		}
		metric(ID, "servicios WMS distintos=" + wmsBases.size());
	}

	@Test
	@Order(5)
	void otherDistributionTypes() {
		heading(ID, "Resto de tipos (una distribución por mediaType, no evaluables primero)");
		Map<String, List<Dist>> byType = new TreeMap<>();
		for (Dist d : dists) {
			if (d.kind() == Kind.OTHER || (d.kind() == Kind.API && !d.mediaType().contains("json")
					&& !"application/api".equals(d.mediaType()))) {
				byType.computeIfAbsent(d.mediaType().isEmpty() ? "(vacío)" : d.mediaType(), k -> new ArrayList<>())
						.add(d);
			}
		}
		List<List<String>> rows = new ArrayList<>();
		for (var entry : byType.entrySet()) {
			Dist d = sample(entry.getValue(), 1).get(0);
			if (d.url().isEmpty()) {
				rows.add(List.of(entry.getKey(), String.valueOf(entry.getValue().size()), d.label(), "-", "-", "-",
						"-", "-", "sin URL"));
				continue;
			}
			Response r = api.tryGet(d.url());
			probes.add(new Probe(Kind.OTHER, entry.getKey(), r.status(), r.elapsed().toMillis(), r.body().length()));
			String dates = feedDates(r.body());
			rows.add(List.of(entry.getKey(), String.valueOf(entry.getValue().size()), d.label(),
					String.valueOf(r.status()), r.contentType(), String.valueOf(r.body().length()),
					String.valueOf(r.elapsed().toMillis()),
					r.header("Last-Modified") == null ? "-" : r.header("Last-Modified"),
					r.failed() ? r.body() : dates + " " + shortUrl(d.url())));
		}
		table(ID, List.of("mediaType", "n", "dataset", "status", "content-type", "bytes", "ms", "Last-Modified",
				"fechas en el cuerpo / url"), rows);
	}

	@Test
	@Order(6)
	void perServiceApiDefinition() {
		heading(ID, "apiDefinition por servicio (`/sede/servicio/catalogo/api/<servicio>.json`)");
		List<Dist> withDefinition = dists.stream().filter(d -> !d.apiDefinition().isEmpty()).toList();
		metric(ID, "distribuciones con apiDefinition=" + withDefinition.size());
		int n = 0;
		for (Dist d : sample(withDefinition, 4)) {
			String url = clean(d.apiDefinition());
			Response r = api.tryGet(url);
			probes.add(new Probe(Kind.API, "apiDefinition", r.status(), r.elapsed().toMillis(), r.body().length()));
			String detail = "";
			if (r.status() == 200 && r.isJson() && tryJson(r) != null) {
				JsonNode root = tryJson(r);
				var params = new TreeSet<String>();
				root.path("paths").properties().forEach(p -> p.getValue().properties().forEach(op -> op.getValue()
						.path("parameters").forEach(param -> params.add(text(param, "name")))));
				detail = "swagger=" + text(root, "swagger") + " paths=" + root.path("paths").size() + " parámetros="
						+ params + " definitions=" + root.path("definitions").size();
				if (n++ == 0) {
					SpikeFixtures.save(FIXTURES, "apidefinition-" + slug(url) + ".json", r.body());
				}
			}
			metric(ID, "- " + d.label() + " " + shortUrl(url) + " -> " + r.status() + " " + r.contentType() + " bytes="
					+ r.body().length() + " ms=" + r.elapsed().toMillis() + " " + detail);
		}
	}

	@Test
	@Order(7)
	void costProjection() {
		heading(ID, "Coste observado por tipo de petición");
		Map<String, List<Probe>> byWhat = new TreeMap<>();
		for (Probe p : probes) {
			byWhat.computeIfAbsent(p.kind() + " " + p.what(), k -> new ArrayList<>()).add(p);
		}
		List<List<String>> rows = new ArrayList<>();
		byWhat.forEach((k, list) -> {
			List<Long> t = list.stream().map(Probe::ms).sorted().toList();
			long ok = list.stream().filter(p -> p.status() >= 200 && p.status() < 300).count();
			long bytes = list.stream().mapToLong(Probe::bytes).sum();
			rows.add(List.of(k, String.valueOf(list.size()), String.valueOf(ok),
					String.valueOf(SpikeJson.percentile(t, 0.5)), String.valueOf(SpikeJson.percentile(t, 0.9)),
					String.valueOf(t.get(t.size() - 1)), String.valueOf(t.stream().mapToLong(Long::longValue).sum()),
					String.valueOf(bytes)));
		});
		table(ID, List.of("petición", "n", "2xx", "p50 ms", "p90 ms", "max ms", "total ms", "bytes"), rows);
		long total = probes.stream().mapToLong(Probe::ms).sum();
		metric(ID, "peticiones=" + probes.size() + " tiempo total=" + Duration.ofMillis(total) + " bytes="
				+ probes.stream().mapToLong(Probe::bytes).sum());

		heading(ID, "Proyección: observar una distribución por ficha (la mejor disponible)");
		Map<Kind, Integer> perKind = new TreeMap<>();
		for (JsonNode d : catalog) {
			Kind best = bestKind(d.path("id").asInt());
			if (best != null) {
				perKind.merge(best, 1, Integer::sum);
			}
		}
		perKind.forEach((k, n) -> {
			List<Long> t = probes.stream().filter(p -> p.kind() == k).map(Probe::ms).sorted().toList();
			long p50 = t.isEmpty() ? -1 : SpikeJson.percentile(t, 0.5);
			metric(ID, "- " + k + ": " + n + " fichas × p50 " + p50 + " ms ≈ "
					+ Duration.ofMillis(Math.max(0, p50) * n) + " (secuencial; API cuenta 2 peticiones si hay sort)");
		});
	}

	// --- sondeo de endpoints -----------------------------------------------------------------------------------

	static List<String> endpointHeader() {
		return List.of("dataset", "endpoint", "status", "content-type", "forma", "totalCount", "bytes", "ms",
				"campos de fecha (1.er registro)", "sort probado", "máx. fecha (sort desc)", "modified declarado",
				"error");
	}

	/** GET rows=1; forma; totalCount; campos de fecha del primer registro; intenta sort desc por ellos. */
	List<String> probeEndpoint(Dist d, String url) {
		Response r = api.tryGet(url);
		probes.add(new Probe(Kind.API, "rows=1", r.status(), r.elapsed().toMillis(), r.body().length()));
		String shape = "-", total = "-", dateFields = "-", sortTried = "-", maxDate = "-", error = "";
		if (r.failed()) {
			error = r.body();
		}
		else if (r.status() == 200 && r.isJson() && tryJson(r) == null) {
			error = "content-type JSON pero cuerpo no parseable: " + shortBody(r.body());
		}
		else if (r.status() == 200 && r.isJson()) {
			JsonNode root = tryJson(r);
			JsonNode records;
			if (root.isArray()) {
				shape = "array";
				records = root;
			}
			else if (root.path("result").isArray()) {
				shape = "envelope";
				records = root.path("result");
				total = root.path("totalCount").isNumber() ? root.path("totalCount").asString("") : "sin totalCount";
			}
			else if (root.path("records").isArray()) {
				shape = "records";
				records = root.path("records");
				total = root.path("totalRecords").isNumber() ? root.path("totalRecords").asString("")
						: "sin totalRecords";
			}
			else if (root.path("features").isArray()) {
				shape = "geojson";
				records = root.path("features");
				total = root.path("totalCount").isNumber() ? root.path("totalCount").asString("") : "sin totalCount";
			}
			else {
				shape = "objeto " + new TreeSet<>(root.propertyNames());
				records = root.path("(none)");
			}
			JsonNode first = records.path(0);
			if (shape.equals("geojson")) {
				first = first.path("properties");
			}
			if (first.isObject()) {
				var candidates = new LinkedHashMap<String, String>();
				first.properties().forEach(e -> {
					if (DATE_FIELD.matcher(e.getKey()).find() && looksLikeDate(e.getValue())) {
						candidates.put(e.getKey(), text(e.getValue()));
					}
				});
				var nested = SpikeJson.filter(SpikeJson.coverage(List.of(first)), "\\.(" + DATE_FIELD.pattern() + ")")
						.keySet();
				dateFields = candidates.toString() + (nested.isEmpty() ? "" : " anidados=" + nested);
				List<String> attempts = new ArrayList<>();
				int tried = 0;
				for (String field : candidates.keySet()) {
					if (tried++ >= 3) {
						break;
					}
					String sortedUrl = url + "&sort=" + enc(field + " desc");
					Response s = api.tryGet(sortedUrl);
					probes.add(new Probe(Kind.API, "sort", s.status(), s.elapsed().toMillis(), s.body().length()));
					if (s.status() == 200 && s.isJson() && tryJson(s) != null) {
						JsonNode sroot = tryJson(s);
						JsonNode srecords = sroot.isArray() ? sroot
								: sroot.path("result").isArray() ? sroot.path("result")
										: sroot.path("records").isArray() ? sroot.path("records")
												: sroot.path("features");
						JsonNode sfirst = srecords.path(0);
						if (shape.equals("geojson")) {
							sfirst = sfirst.path("properties");
						}
						String value = text(sfirst, field);
						attempts.add(field + "→200");
						maxDate = field + "=" + value;
						if (savedApiFixtures < 6) {
							// Contactos de negocios (alojamientos, restaurantes) fuera del repositorio (regla 22).
							SpikeFixtures.saveRedacted(FIXTURES, "api-" + slug(d.path()) + "-rows1.json", r.body(),
									CONTACT_FIELDS);
							SpikeFixtures.saveRedacted(FIXTURES, "api-" + slug(d.path()) + "-sort-" + field + ".json",
									s.body(), CONTACT_FIELDS);
							savedApiFixtures++;
						}
						break;
					}
					attempts.add(field + "→" + s.status() + (s.isJson() ? " " + shortBody(s.body()) : ""));
				}
				sortTried = attempts.isEmpty() ? "(sin candidatos)" : String.join("; ", attempts);
			}
			else {
				dateFields = "(sin registros)";
			}
		}
		else {
			error = shortBody(r.body());
		}
		return List.of(d.label(), shortUrl(d.path()), String.valueOf(r.status()), r.contentType(), shape, total,
				String.valueOf(r.body().length()), String.valueOf(r.elapsed().toMillis()), dateFields, sortTried,
				maxDate, d.declaredModifiedDate(), error);
	}

	static void summarizeEndpoints(String what, List<List<String>> rows) {
		long ok = rows.stream().filter(r -> r.get(2).equals("200")).count();
		long envelope = rows.stream().filter(r -> r.get(4).equals("envelope")).count();
		long withTotal = rows.stream().filter(r -> r.get(5).matches("\\d+")).count();
		long withDates = rows.stream().filter(r -> r.get(8).startsWith("{") && !r.get(8).startsWith("{}")).count();
		long sortable = rows.stream().filter(r -> !r.get(10).equals("-")).count();
		metric(ID, "**" + what + "**: sondeadas=" + rows.size() + " 200=" + ok + " envelope=" + envelope
				+ " con totalCount=" + withTotal + " con campo de fecha en el 1.er registro=" + withDates
				+ " con sort desc que funciona=" + sortable);
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	/** {@code null} si el cuerpo no es JSON aunque la cabeceras diga que lo es (ocurre en la sede). */
	static JsonNode tryJson(Response r) {
		try {
			return r.json();
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	static String apiUrl(String url, boolean geo) {
		int q = url.indexOf('?');
		String path = q < 0 ? url : url.substring(0, q);
		String query = q < 0 ? "" : url.substring(q + 1);
		if (!path.endsWith(".json") && !path.endsWith(".geojson")) {
			path = path + ".json";
		}
		var params = new ArrayList<String>();
		if (!query.isEmpty()) {
			for (String p : query.split("&")) {
				if (!p.startsWith("rows=") && !p.startsWith("start=") && !p.startsWith("sort=")) {
					params.add(p);
				}
			}
		}
		params.add("rows=1");
		if (geo && params.stream().noneMatch(p -> p.startsWith("srsname="))) {
			params.add("srsname=wgs84");
		}
		return path + "?" + String.join("&", params);
	}

	static List<Dist> sample(List<Dist> all, int max) {
		var seen = new HashSet<Integer>();
		var out = new ArrayList<Dist>();
		List<Dist> sorted = all.stream()
				.sorted(Comparator.comparing((Dist d) -> d.ne() ? 0 : 1).thenComparing(Dist::datasetId))
				.toList();
		for (Dist d : sorted) {
			if (out.size() >= max) {
				break;
			}
			if (seen.add(d.datasetId())) {
				out.add(d);
			}
		}
		return out;
	}

	static String clean(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		String u = url.replace("&amp;", "&").strip();
		if (u.startsWith("/")) {
			u = HOST + u;
		}
		return u;
	}

	static boolean looksLikeDate(JsonNode v) {
		if (v.isNumber()) {
			return v.asString("").matches("(19|20)\\d{6}");
		}
		String s = text(v);
		return SpikeJson.date(v) != null || s.matches("(19|20)\\d{6}") || s.matches("\\d{2}/\\d{2}/(19|20)\\d{2}.*");
	}

	static ZonedDateTime parseLastModified(String header) {
		if (header == null) {
			return null;
		}
		try {
			return ZonedDateTime.parse(header.strip(), ZONE_NAME);
		}
		catch (DateTimeParseException e) {
			try {
				return ZonedDateTime.parse(header.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
			}
			catch (DateTimeParseException e2) {
				return null;
			}
		}
	}

	static String feedDates(String body) {
		var found = new ArrayList<String>();
		for (String tag : List.of("<updated>", "<pubDate>", "<lastBuildDate>", "<dc:date>", "<published>")) {
			int i = body.indexOf(tag);
			if (i >= 0) {
				int end = body.indexOf('<', i + tag.length());
				found.add(tag + (end > i ? body.substring(i + tag.length(), Math.min(end, i + tag.length() + 40)) : ""));
			}
		}
		return found.isEmpty() ? "sin fechas" : String.join(" ", found);
	}

	static String shortUrl(String url) {
		return url.replace(HOST, "").replace("https://", "");
	}

	static String shortBody(String body) {
		String s = body.strip().replace("\n", " ").replace("|", "/");
		return s.length() > 90 ? s.substring(0, 90) + "…" : s;
	}

	static String slug(String s) {
		String slug = s.replace(HOST, "").replace("https://", "").replaceAll("[^A-Za-z0-9]+", "-")
				.replaceAll("^-|-$", "");
		return slug.length() > 60 ? slug.substring(slug.length() - 60) : slug;
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
