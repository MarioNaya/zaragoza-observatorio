package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.DATA_SPACE;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S1.2 — Inventario de endpoints: el Swagger de la API frente al catálogo (docs/ESTADO.md §4 paso 1). Informe:
 * docs/spikes/S1.2-inventario-api.md.
 * <p>
 * Preguntas: (a) qué forma exacta tiene {@code sede/servicio/catalogo/api.json} (Swagger 2.0) y qué cabeceras
 * devuelve, para ingerirlo como documento; (b) cuántas fichas con distribución {@code application/api} casan por
 * tag con el Swagger y si el {@code downloadURL} que declaran está documentado en su tag; (c) para las fichas cuyo
 * endpoint declarado no responde (404, 303, HTML, objeto sin lista; S1.1), si alguno de los paths documentados en
 * su tag sí responde, y si la elección sería unívoca.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S12ApiInventorySpike {

	static final String ID = "S1.2-inventario-api";
	static final String HOST = "https://www.zaragoza.es";
	static final String SWAGGER = SEDE + "/catalogo/api.json";
	static final String FIXTURES = "catalog/observation";
	static final int MAX_ALTERNATIVES = 8;
	/**
	 * Campos de contacto y de personas que se redactan en los fixtures (regla 22): los listados de asociaciones
	 * traen nombre del presidente, teléfono y correo.
	 */
	static final Set<String> REDACTED_FIELDS = Set.of("email", "mail", "telefonos", "telefono", "phone", "fax",
			"comment", "president", "presidente", "contacto", "contact", "responsable");
	/**
	 * Fichas cuya alternativa {@code <declarado>/list} se graba como fixture para los tests del adaptador: 132
	 * (índice con 303) y 247 (tilde en el {@code downloadURL}). Las de artistas (1920) y premios (1760) traen
	 * datos de personas y no se graban.
	 */
	static final Set<Integer> FIXTURE_DATASETS = Set.of(132, 247);

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(90));
	static JsonNode swagger;
	static String base;
	static final List<Op> ops = new ArrayList<>();
	static final Map<String, List<Op>> byTag = new TreeMap<>();
	static final List<ApiDataset> apiDatasets = new ArrayList<>();
	static final List<Probe> probes = new ArrayList<>();

	/** Una operación del Swagger: {@code paths.<path>.<method>}. */
	record Op(String tag, String method, String path, String summary, List<String> produces) {

		boolean templated() {
			return path.contains("{");
		}

		String url() {
			return base + path;
		}
	}

	/** Una ficha del catálogo con distribución {@code application/api}. */
	record ApiDataset(int id, String title, boolean geo, String tag, String accessUrl, String downloadUrl) {

		boolean matched() {
			return tag != null && byTag.containsKey(tag);
		}

		/** {@code downloadURL} como path del Swagger: sin host, sin el basePath {@code /sede}, sin barra final ni query. */
		String declaredPath() {
			if (downloadUrl == null) {
				return null;
			}
			String p = downloadUrl.replace("&amp;", "&").strip();
			int q = p.indexOf('?');
			if (q >= 0) {
				p = p.substring(0, q);
			}
			if (p.startsWith(HOST)) {
				p = p.substring(HOST.length());
			}
			if (p.startsWith("/sede/")) {
				p = p.substring("/sede".length());
			}
			if (p.endsWith("/") && p.length() > 1) {
				p = p.substring(0, p.length() - 1);
			}
			return p;
		}

		String declaredUrl() {
			String p = downloadUrl.replace("&amp;", "&").strip();
			int q = p.indexOf('?');
			if (q >= 0) {
				p = p.substring(0, q);
			}
			if (p.endsWith("/")) {
				p = p.substring(0, p.length() - 1);
			}
			return (p.startsWith("/") ? HOST + p : p) + ".json?rows=1" + (geo ? "&srsname=wgs84" : "");
		}

		String label() {
			return id + " «" + (title.length() > 40 ? title.substring(0, 40) + "…" : title) + "»";
		}
	}

	record Probe(String what, int status, long ms, long bytes) {
	}

	/** Forma de la respuesta de un endpoint con {@code rows=1}. */
	record Shape(String form, Integer total) {

		boolean listing() {
			return form.equals("array") || form.equals("envoltorio") || form.equals("records")
					|| form.equals("geojson");
		}
	}

	@BeforeAll
	static void load() {
		SpikeFixtures.startMetrics(ID);
		heading(ID, "Catálogo: fichas con distribución application/api");
		var catalog = api.get(DATA_SPACE + "/catalogo.json?rows=500&start=0&fl=id,title,geo,formato");
		metric(ID, catalog.summary());
		assertThat(catalog.status()).isEqualTo(200);
		for (JsonNode d : catalog.json().path("result")) {
			for (JsonNode f : d.path("formato")) {
				if ("application/api".equals(text(f, "mediaType"))) {
					String access = text(f, "accessURL");
					String tag = null;
					int hash = access.indexOf("#/");
					if (hash >= 0) {
						tag = URLDecoder.decode(access.substring(hash + 2), StandardCharsets.UTF_8).strip();
						tag = tag.isEmpty() ? null : tag;
					}
					String download = text(f, "downloadURL");
					apiDatasets.add(new ApiDataset(d.path("id").asInt(), text(d, "title"), "S".equals(text(d, "geo")),
							tag, access.isEmpty() ? null : access, download.isEmpty() ? null : download));
					break;
				}
			}
		}
		metric(ID, "fichas con application/api=" + apiDatasets.size() + " con tag en accessURL="
				+ apiDatasets.stream().filter(d -> d.tag() != null).count() + " con downloadURL="
				+ apiDatasets.stream().filter(d -> d.downloadUrl() != null).count());

		heading(ID, "Descarga del Swagger");
		var r = api.get(SWAGGER);
		metric(ID, r.summary());
		probes.add(new Probe("GET api.json", r.status(), r.elapsed().toMillis(), r.body().length()));
		assertThat(r.status()).isEqualTo(200);
		assertThat(r.isJson()).isTrue();
		SpikeFixtures.save("catalog", "swagger-api.json", r.body());
		SpikeFixtures.saveHeaders("catalog", "swagger-api.headers", r.status(), r.headers());
		for (String h : List.of("Content-Type", "Content-Length", "Last-Modified", "ETag", "Cache-Control", "Expires",
				"Vary", "Content-Encoding")) {
			metric(ID, "- " + h + ": " + r.header(h));
		}
		swagger = r.json();
		String scheme = swagger.path("schemes").path(0).asString("https");
		base = scheme + "://" + text(swagger, "host") + text(swagger, "basePath");
		swagger.path("paths").properties().forEach(p -> p.getValue().properties().forEach(op -> {
			String path = p.getKey().startsWith("/") ? p.getKey() : "/" + p.getKey();
			List<String> produces = op.getValue().path("produces").valueStream().map(JsonNode::asString).toList();
			List<String> tags = op.getValue().path("tags").valueStream().map(JsonNode::asString).toList();
			for (String tag : tags.isEmpty() ? List.of("(sin tag)") : tags) {
				Op o = new Op(tag, op.getKey(), path, text(op.getValue(), "summary"), produces);
				ops.add(o);
				byTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(o);
			}
		}));
	}

	@Test
	@Order(1)
	void documentShape() {
		heading(ID, "El documento api.json");
		metric(ID, "swagger=" + text(swagger, "swagger") + " title=«" + text(swagger.path("info"), "title")
				+ "» version=" + text(swagger.path("info"), "version") + " contact="
				+ text(swagger.path("info").path("contact"), "email") + " host=" + text(swagger, "host") + " basePath="
				+ text(swagger, "basePath") + " schemes=" + swagger.path("schemes") + " -> base=" + base);
		metric(ID, "claves raíz=" + swagger.propertyNames() + " paths=" + swagger.path("paths").size()
				+ " operaciones=" + ops.size() + " definitions=" + swagger.path("definitions").size() + " tags raíz="
				+ (swagger.has("tags") ? swagger.path("tags").size() : "(no existe)"));
		Map<String, Integer> byMethod = new TreeMap<>();
		Map<String, Integer> produces = new TreeMap<>();
		Map<String, Integer> opKeys = new TreeMap<>();
		int withSummary = 0;
		int templated = 0;
		int noSlash = 0;
		var noSlashPaths = new ArrayList<String>();
		int maxPath = 0;
		int maxTag = 0;
		int maxSummary = 0;
		swagger.path("paths").properties().forEach(p -> {
			if (!p.getKey().startsWith("/")) {
				noSlashPaths.add(p.getKey());
			}
			p.getValue().properties().forEach(op -> op.getValue().propertyNames().forEach(k -> opKeys.merge(k, 1, Integer::sum)));
		});
		noSlash = noSlashPaths.size();
		for (Op o : ops) {
			byMethod.merge(o.method(), 1, Integer::sum);
			o.produces().forEach(pr -> produces.merge(pr, 1, Integer::sum));
			if (!o.summary().isBlank()) {
				withSummary++;
			}
			if (o.templated()) {
				templated++;
			}
			maxPath = Math.max(maxPath, o.path().length());
			maxTag = Math.max(maxTag, o.tag().length());
			maxSummary = Math.max(maxSummary, o.summary().length());
		}
		counts(ID, "método", byMethod);
		counts(ID, "clave de operación", opKeys);
		metric(ID, "tags distintos=" + byTag.size() + " operaciones sin tag=" + byTag.getOrDefault("(sin tag)", List.of()).size()
				+ " con summary=" + withSummary + " con plantilla {…}=" + templated + " paths sin barra inicial=" + noSlash
				+ " " + noSlashPaths.stream().limit(10).toList());
		metric(ID, "longitud máxima: path=" + maxPath + " tag=" + maxTag + " summary=" + maxSummary);
		metric(ID, "paths por tag: mín=" + byTag.values().stream().mapToInt(List::size).min().orElse(0) + " máx="
				+ byTag.values().stream().mapToInt(List::size).max().orElse(0));
		counts(ID, "produces", produces);
		List<List<String>> rows = new ArrayList<>();
		byTag.forEach((tag, list) -> rows.add(List.of(tag, String.valueOf(list.size()),
				String.valueOf(list.stream().filter(o -> !o.templated()).count()),
				list.stream().filter(o -> !o.templated()).map(Op::path).findFirst().orElse("-"))));
		table(ID, List.of("tag", "operaciones", "sin plantilla", "primer path sin plantilla"), rows);

		heading(ID, "Parámetros y HEAD sobre api.json");
		Response head = api.tryHead(SWAGGER);
		probes.add(new Probe("HEAD api.json", head.status(), head.elapsed().toMillis(), 0));
		metric(ID, "HEAD -> " + head.status() + " " + head.contentType() + " Content-Length=" + head.header("Content-Length")
				+ " Last-Modified=" + head.header("Last-Modified") + " ETag=" + head.header("ETag"));
		Response paged = api.tryGet(SWAGGER + "?rows=1&start=0");
		probes.add(new Probe("GET api.json?rows=1&start=0", paged.status(), paged.elapsed().toMillis(), paged.body().length()));
		metric(ID, "GET ?rows=1&start=0 -> " + paged.status() + " " + paged.contentType() + " bytes=" + paged.body().length()
				+ " paths=" + (paged.status() == 200 && paged.isJson() ? paged.json().path("paths").size() : "-")
				+ " (sin parámetros: paths=" + swagger.path("paths").size() + ")");
	}

	@Test
	@Order(2)
	void crossWithCatalogTags() {
		heading(ID, "Cruce por tag: fichas con application/api frente al Swagger");
		List<ApiDataset> tagged = apiDatasets.stream().filter(d -> d.tag() != null).toList();
		List<ApiDataset> matched = tagged.stream().filter(ApiDataset::matched).toList();
		List<ApiDataset> unmatched = tagged.stream().filter(d -> !d.matched()).toList();
		metric(ID, "fichas con tag=" + tagged.size() + " casan con un tag del Swagger=" + matched.size() + " no casan="
				+ unmatched.size() + " tags distintos usados=" + matched.stream().map(ApiDataset::tag).distinct().count()
				+ "/" + byTag.size());
		for (ApiDataset d : unmatched) {
			metric(ID, "- no casa: " + d.label() + " tag=«" + d.tag() + "» downloadURL=" + d.downloadUrl());
		}
		int documented = 0;
		int documentedElsewhere = 0;
		var allPaths = new HashSet<String>();
		ops.forEach(o -> allPaths.add(o.path()));
		List<List<String>> rows = new ArrayList<>();
		for (ApiDataset d : matched) {
			String declared = d.declaredPath();
			List<Op> tagOps = byTag.get(d.tag());
			boolean inTag = declared != null && tagOps.stream().anyMatch(o -> o.path().equals(declared));
			boolean anywhere = declared != null && allPaths.contains(declared);
			if (inTag) {
				documented++;
			}
			else if (anywhere) {
				documentedElsewhere++;
			}
			rows.add(List.of(String.valueOf(d.id()), d.title(), d.tag(), declared == null ? "(sin downloadURL)" : declared,
					inTag ? "sí" : anywhere ? "en otro tag" : "no", String.valueOf(tagOps.size()),
					String.valueOf(tagOps.stream().filter(o -> !o.templated()).count())));
		}
		metric(ID, "de las que casan: downloadURL documentado en su tag=" + documented + " documentado en otro tag="
				+ documentedElsewhere + " no documentado=" + (matched.size() - documented - documentedElsewhere));
		table(ID, List.of("id", "título", "tag", "path declarado (downloadURL)", "documentado en el tag",
				"operaciones del tag", "sin plantilla"), rows);

		Map<String, List<Integer>> datasetsByTag = new TreeMap<>();
		matched.forEach(d -> datasetsByTag.computeIfAbsent(d.tag(), k -> new ArrayList<>()).add(d.id()));
		metric(ID, "tags con más de una ficha: " + datasetsByTag.entrySet().stream()
				.filter(e -> e.getValue().size() > 1).map(e -> e.getKey() + " " + e.getValue()).toList());
		var unused = new TreeSet<>(byTag.keySet());
		unused.removeAll(datasetsByTag.keySet());
		metric(ID, "tags del Swagger sin ficha (" + unused.size() + "): " + unused);
		metric(ID, "operaciones en tags sin ficha=" + unused.stream().mapToInt(t -> byTag.get(t).size()).sum());
	}

	@Test
	@Order(3)
	void probeDeclaredAndDocumentedPaths() {
		heading(ID, "Endpoint declarado (downloadURL + .json?rows=1) de cada ficha con application/api");
		List<List<String>> rows = new ArrayList<>();
		List<ApiDataset> failing = new ArrayList<>();
		for (ApiDataset d : apiDatasets) {
			if (d.downloadUrl() == null) {
				rows.add(List.of(String.valueOf(d.id()), d.title(), d.matched() ? "sí" : "no", "(sin downloadURL)", "-", "-", "-", "-"));
				continue;
			}
			String url = d.declaredUrl();
			Response r = api.tryGet(url);
			probes.add(new Probe("GET declarado rows=1", r.status(), r.elapsed().toMillis(), r.body().length()));
			Shape shape = classify(r);
			if (!shape.listing()) {
				failing.add(d);
			}
			rows.add(List.of(String.valueOf(d.id()), d.title(), d.matched() ? "sí" : "no", shortUrl(url),
					String.valueOf(r.status()), shape.form(), shape.total() == null ? "-" : String.valueOf(shape.total()),
					String.valueOf(r.elapsed().toMillis())));
		}
		table(ID, List.of("id", "título", "tag casa", "url probada", "status", "forma", "totalCount", "ms"), rows);
		long probed = apiDatasets.stream().filter(d -> d.downloadUrl() != null).count();
		metric(ID, "endpoints declarados probados=" + probed + " con lista de registros=" + (probed - failing.size())
				+ " sin lista (fallo o forma no listable)=" + failing.size() + " " + failing.stream().map(ApiDataset::id).toList());

		heading(ID, "Alternativas documentadas en el tag para las fichas cuyo endpoint declarado no responde con lista");
		List<List<String>> alt = new ArrayList<>();
		var withAlternative = new LinkedHashMap<Integer, List<String>>();
		var withoutAlternative = new ArrayList<Integer>();
		for (ApiDataset d : failing) {
			if (!d.matched()) {
				withoutAlternative.add(d.id());
				alt.add(List.of(String.valueOf(d.id()), d.title(), "(el tag no existe en el Swagger)", "-", "-", "-", "-", "-"));
				continue;
			}
			String declared = d.declaredPath();
			List<Op> candidates = byTag.get(d.tag()).stream()
					.filter(o -> o.method().equals("get") && !o.templated() && !o.path().equals(declared))
					.limit(MAX_ALTERNATIVES).toList();
			if (candidates.isEmpty()) {
				withoutAlternative.add(d.id());
				alt.add(List.of(String.valueOf(d.id()), d.title(), "(sin paths GET sin plantilla distintos del declarado)", "-", "-", "-", "-", "-"));
				continue;
			}
			boolean saved = false;
			for (Op o : candidates) {
				String url = o.url() + ".json?rows=1" + (d.geo() ? "&srsname=wgs84" : "");
				Response r = api.tryGet(url);
				probes.add(new Probe("GET alternativa rows=1", r.status(), r.elapsed().toMillis(), r.body().length()));
				Shape shape = classify(r);
				String fixture = "-";
				if (shape.listing()) {
					withAlternative.computeIfAbsent(d.id(), k -> new ArrayList<>()).add(o.path());
					if (!saved && FIXTURE_DATASETS.contains(d.id()) && o.path().equals(unaccent(declared) + "/list")) {
						String name = "api-" + slug(o.url()) + "-rows1.json";
						SpikeFixtures.saveRedacted(FIXTURES, name, r.body(), REDACTED_FIELDS);
						fixture = name;
						saved = true;
					}
				}
				alt.add(List.of(String.valueOf(d.id()), d.title(), o.path(), o.summary().isBlank() ? "-" : o.summary(),
						String.valueOf(r.status()), shape.form(), shape.total() == null ? "-" : String.valueOf(shape.total()),
						fixture));
			}
			if (!withAlternative.containsKey(d.id())) {
				withoutAlternative.add(d.id());
			}
		}
		table(ID, List.of("id", "título", "path documentado probado", "summary", "status", "forma", "totalCount", "fixture"), alt);
		metric(ID, "fichas con endpoint declarado fallido=" + failing.size() + "; con al menos un path documentado que responde con lista="
				+ withAlternative.size() + "; sin alternativa=" + withoutAlternative.size() + " " + withoutAlternative);
		withAlternative.forEach((id, paths) -> metric(ID, "- " + id + ": " + paths.size() + " path(s) responden: " + paths));
		var unique = new ArrayList<String>();
		for (ApiDataset d : failing) {
			String declared = d.declaredPath();
			if (d.matched() && declared != null) {
				String list = unaccent(declared) + "/list";
				boolean documented = byTag.get(d.tag()).stream().anyMatch(o -> o.path().equals(list));
				boolean responds = withAlternative.getOrDefault(d.id(), List.of()).contains(list);
				if (documented) {
					unique.add(d.id() + " " + list + (responds ? " (responde)" : " (no responde)"));
				}
			}
		}
		metric(ID, "regla candidata «<declarado>/list documentado en el tag» (sin tildes): " + unique.size() + " fichas " + unique);
	}

	@Test
	@Order(4)
	void cost() {
		heading(ID, "Coste");
		Map<String, List<Probe>> byWhat = new TreeMap<>();
		for (Probe p : probes) {
			byWhat.computeIfAbsent(p.what(), k -> new ArrayList<>()).add(p);
		}
		List<List<String>> rows = new ArrayList<>();
		byWhat.forEach((k, list) -> {
			List<Long> t = list.stream().map(Probe::ms).sorted().toList();
			long ok = list.stream().filter(p -> p.status() >= 200 && p.status() < 300).count();
			rows.add(List.of(k, String.valueOf(list.size()), String.valueOf(ok), String.valueOf(SpikeJson.percentile(t, 0.5)),
					String.valueOf(t.get(t.size() - 1)), String.valueOf(list.stream().mapToLong(Probe::bytes).sum())));
		});
		table(ID, List.of("petición", "n", "2xx", "p50 ms", "max ms", "bytes"), rows);
		metric(ID, "peticiones=" + probes.size() + " tiempo total=" + Duration.ofMillis(probes.stream().mapToLong(Probe::ms).sum()));
	}

	// --- helpers --------------------------------------------------------------------------------------------------

	static Shape classify(Response r) {
		if (r.status() != 200) {
			return new Shape("HTTP " + r.status() + (r.status() < 0 ? " " + shortBody(r.body()) : " " + r.contentType()
					+ (r.header("Location") == null ? "" : " -> " + r.header("Location"))), null);
		}
		if (!r.isJson()) {
			return new Shape("no JSON (" + r.contentType() + ")", null);
		}
		JsonNode root;
		try {
			root = r.body().isBlank() ? null : r.json();
		}
		catch (RuntimeException e) {
			root = null;
		}
		if (root == null || root.isMissingNode()) {
			return new Shape("JSON vacío o inválido", null);
		}
		if (root.isArray()) {
			return new Shape("array", null);
		}
		if (root.path("result").isArray() || root.path("totalCount").isNumber()) {
			return new Shape("envoltorio", root.path("totalCount").isNumber() ? root.path("totalCount").asInt() : null);
		}
		if (root.path("records").isArray()) {
			return new Shape("records", root.path("totalRecords").isNumber() ? root.path("totalRecords").asInt() : null);
		}
		if (root.path("features").isArray()) {
			return new Shape("geojson", root.path("totalCount").isNumber() ? root.path("totalCount").asInt() : null);
		}
		return new Shape("objeto sin lista: " + root.propertyNames().stream().limit(6).toList(), null);
	}

	static String shortUrl(String url) {
		return url.replace(HOST, "");
	}

	/** Sin marcas diacríticas: {@code clavo-topográfico} → {@code clavo-topografico}. */
	static String unaccent(String s) {
		return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
	}

	static String shortBody(String body) {
		String s = body.strip().replace("\n", " ").replace("|", "/");
		return s.length() > 80 ? s.substring(0, 80) + "…" : s;
	}

	static String slug(String s) {
		String slug = s.replace(HOST, "").replace("https://", "").toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		return slug.length() > 60 ? slug.substring(slug.length() - 60) : slug;
	}

}
