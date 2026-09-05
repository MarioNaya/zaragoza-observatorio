package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.DATA_SPACE;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;

import java.util.ArrayList;
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
import es.zaragoza.observatory.spikes.support.SpikeJson;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import tools.jackson.databind.JsonNode;

/**
 * S0.4 — Geo (SPEC.md §3): geometrías de juntas/barrios y padrón por barrio. Informe: docs/spikes/S0.4-geo.md.
 * <p>
 * Preguntas: qué devuelve {@code /servicio/distrito*}, si hay geometrías de barrio en la API o en el catálogo,
 * si existe padrón/población por barrio y con qué granularidad y periodicidad, y qué es {@code barrio_code} en quejas.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S04GeoSpike {

	static final String ID = "S0.4-geo";
	static final Pattern TERRITORY = Pattern.compile(
			"barrio|junta|distrito|padr[oó]n|poblaci[oó]n|censo|habitantes|demogr|secci[oó]n censal|callejero|portal|vulnerabilidad|sociodemogr",
			Pattern.CASE_INSENSITIVE);

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient();
	static List<JsonNode> catalog = new ArrayList<>();

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
		var r = api.get(DATA_SPACE
				+ "/catalogo.json?rows=500&fl=id,title,description_basic,geo,geoname,formato,accrualPeriodicity,modified,keywords,tag,spatialCoverageWKT,dcatSpatialReference,explorable");
		if (r.status() == 200 && r.isJson()) {
			r.json().path("result").forEach(catalog::add);
		}
		metric(ID, "catálogo cargado: " + catalog.size() + " datasets");
	}

	@Test
	@Order(1)
	void districtsEndpoints() {
		heading(ID, "`/servicio/distrito*`: juntas municipales y vecinales");
		for (String path : List.of("/distrito.json", "/distrito.json?rows=100", "/distrito.json?srsname=wgs84&rows=100",
				"/distrito/municipal.json", "/distrito/municipal.json?srsname=wgs84", "/distrito/vecinal.json?srsname=wgs84",
				"/distrito/municipal.geojson?srsname=wgs84")) {
			var r = api.get(SEDE + path);
			List<JsonNode> items = itemsOf(r);
			String geometry = items.isEmpty() ? "-" : describeGeometry(items.get(0));
			metric(ID, "`" + path + "` -> " + r.status() + " " + r.contentType() + " bytes=" + r.body().length()
					+ " devueltos=" + items.size() + " totalCount=" + (r.isJson() && r.status() == 200 ? text(r.json(), "totalCount") : "-")
					+ " lastModified=" + r.header("Last-Modified") + " geometría primero: " + geometry);
			if (r.status() == 200 && items.size() > 0) {
				SpikeFixtures.save("geo", path.substring(1).replace('/', '-').replace('?', '_').replace('&', '_').replace('=', '-'),
						r.body());
			}
			if (path.equals("/distrito/municipal.json?srsname=wgs84") && !items.isEmpty()) {
				metric(ID, "caminos del primero: " + SpikeJson.paths(items.get(0)));
				metric(ID, "ids/títulos: " + items.stream().map(i -> text(i, "id") + "=" + text(i, "title")).toList());
			}
			if (path.equals("/distrito/vecinal.json?srsname=wgs84") && !items.isEmpty()) {
				metric(ID, "ids/títulos vecinales: " + items.stream().map(i -> text(i, "id") + "=" + text(i, "title")).toList());
			}
		}
		heading(ID, "`/servicio/distrito/{id}.json` (detalle con indicadores/equipamientos)");
		var municipal = itemsOf(api.get(SEDE + "/distrito/municipal.json?srsname=wgs84"));
		if (!municipal.isEmpty()) {
			String id = text(municipal.get(0), "id");
			for (String q : List.of("", "?indicadores=true", "?indicadores=true&equipamientos=false&eventos=false&asociaciones=false&plenos=false")) {
				var d = api.get(SEDE + "/distrito/" + id + ".json" + q);
				metric(ID, "`distrito/" + id + ".json" + q + "` -> " + d.status() + " bytes=" + d.body().length()
						+ " lastModified=" + d.header("Last-Modified") + " caminos top: "
						+ (d.status() == 200 && d.isJson() ? SpikeJson.paths(d.json()).stream().filter(p -> !p.contains(".")).toList() : List.of()));
				if (d.status() == 200 && d.isJson()) {
					SpikeFixtures.save("geo", "distrito-" + id + (q.isEmpty() ? "" : "-indicadores") + ".json", d.body());
					JsonNode ind = d.json().path("indicadores");
					if (!ind.isMissingNode()) {
						metric(ID, "indicadores: tipo=" + ind.getNodeType() + " tamaño=" + ind.size() + " muestra="
								+ snippet(ind.toString(), 600));
					}
				}
			}
		}
	}

	@Test
	@Order(2)
	void territoryDatasetsInCatalog() {
		heading(ID, "Datasets del catálogo cuyo título/keywords/tag mencionan barrio, junta, padrón, población…");
		List<List<String>> rows = new ArrayList<>();
		for (JsonNode d : catalog) {
			String haystack = text(d, "title") + " " + text(d, "keywords") + " " + text(d, "tag") + " " + text(d, "description_basic");
			if (!TERRITORY.matcher(text(d, "title") + " " + text(d, "keywords") + " " + text(d, "tag")).find()) {
				continue;
			}
			var mediaTypes = new TreeSet<String>();
			for (JsonNode f : d.path("formato")) {
				mediaTypes.add(text(f, "mediaType"));
			}
			rows.add(List.of(text(d, "id"), text(d, "title").replace("|", "/"), text(d, "accrualPeriodicity"),
					text(d, "modified").length() >= 10 ? text(d, "modified").substring(0, 10) : "", text(d, "geo"),
					text(d, "explorable"), String.join(" ", mediaTypes)));
			metric(ID, "- id=" + text(d, "id") + " «" + text(d, "title") + "» keywords=" + snippet(text(d, "keywords"), 160)
					+ " descr=" + snippet(text(d, "description_basic"), 200));
		}
		table(ID, List.of("id", "título", "periodicidad", "modified", "geo", "explorable", "formatos"), rows);
	}

	@Test
	@Order(3)
	void fetchCandidateDistributions() {
		heading(ID, "Descarga de distribuciones candidatas (barrios / padrón): estado, tipo, tamaño y estructura");
		Pattern strong = Pattern.compile("barrio|padr[oó]n|poblaci[oó]n|junta|distrito|secci[oó]n", Pattern.CASE_INSENSITIVE);
		Pattern usable = Pattern.compile("json|csv|wfs|geo|api", Pattern.CASE_INSENSITIVE);
		int requests = 0;
		for (JsonNode d : catalog) {
			if (!strong.matcher(text(d, "title")).find()) {
				continue;
			}
			for (JsonNode f : d.path("formato")) {
				String mt = text(f, "mediaType");
				String url = text(f, "accessURL");
				if (url.isEmpty() || !usable.matcher(mt).find() || requests >= 18 || url.contains("docs-api_sede")) {
					continue;
				}
				requests++;
				ZaragozaSpikeClient.Response r;
				try {
					r = api.get(url.contains(" ") ? url.replace(" ", "%20") : url);
				}
				catch (RuntimeException e) {
					// El catálogo contiene URLs malformadas (p. ej. "https://www,zaragoza.es/..."): se registran, no abortan.
					metric(ID, "- dataset " + text(d, "id") + " «" + text(d, "title") + "» " + mt + " -> URL INVÁLIDA: " + url + " (" + e.getMessage() + ")");
					continue;
				}
				String structure = "-";
				if (r.status() == 200 && r.isJson()) {
					try {
						JsonNode j = r.json();
						structure = j.isObject() ? "obj keys=" + new TreeSet<>(j.propertyNames()) + featureInfo(j)
								: "array n=" + j.size() + (j.size() > 0 ? " keys=" + new TreeSet<>(j.get(0).propertyNames()) : "");
					}
					catch (RuntimeException e) {
						structure = "json inválido: " + e.getMessage();
					}
				}
				else if (r.status() == 200) {
					structure = "primeras líneas: " + snippet(r.body(), 300);
				}
				metric(ID, "- dataset " + text(d, "id") + " «" + text(d, "title") + "» " + mt + " -> " + r.status() + " "
						+ r.contentType() + " bytes=" + r.body().length() + " ms=" + r.elapsed().toMillis() + " url=" + url);
				metric(ID, "    " + snippet(structure, 700));
				if (r.status() == 200 && r.body().length() < 3_000_000 && r.isJson()) {
					SpikeFixtures.save("geo", "dataset-" + text(d, "id") + "-" + text(f, "id") + ".json", r.body());
				}
			}
		}
		metric(ID, "peticiones a distribuciones=" + requests);
	}

	@Test
	@Order(4)
	void barrioCodeInComplaintsAndOtherApis() {
		heading(ID, "`barrio_code`/`district` en quejas y referencias a barrio en otros endpoints");
		var r = api.get(SEDE + "/quejas-sugerencias/list.json?rows=500&fl=barrio_code,district&sort=requested_datetime%20desc");
		Map<String, Integer> pairs = new TreeMap<>();
		for (JsonNode i : itemsOf(r)) {
			pairs.merge(text(i, "district") + " / barrio_code=" + text(i, "barrio_code"), 1, Integer::sum);
		}
		metric(ID, "pares district/barrio_code en 500 quejas recientes -> " + r.status() + " distintos=" + pairs.size());
		pairs.forEach((k, v) -> metric(ID, "- " + k + " : " + v));
		for (String path : List.of("/equipamiento.json?rows=2", "/agenda-institucional/junta.json?rows=2",
				"/registro-licencia/portal.json?rows=2&srsname=wgs84", "/callejero.json?rows=2", "/licencia-obra.json?rows=2&srsname=wgs84")) {
			var e = api.get(SEDE + path);
			List<JsonNode> items = itemsOf(e);
			metric(ID, "`" + path + "` -> " + e.status() + " devueltos=" + items.size() + " caminos="
					+ (items.isEmpty() ? "-" : SpikeJson.paths(items.get(0))));
		}
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	static List<JsonNode> itemsOf(ZaragozaSpikeClient.Response r) {
		List<JsonNode> out = new ArrayList<>();
		if (r.status() == 200 && r.isJson()) {
			JsonNode n = r.json();
			JsonNode arr = n.isArray() ? n : n.has("result") ? n.path("result") : n.has("features") ? n.path("features") : n;
			if (arr.isArray()) {
				arr.forEach(out::add);
			}
			else if (arr.isObject() && !arr.isEmpty()) {
				out.add(arr);
			}
		}
		return out;
	}

	static String describeGeometry(JsonNode item) {
		JsonNode g = item.path("geometry");
		if (g.isMissingNode() || g.isNull()) {
			return "sin geometry (caminos: " + SpikeJson.paths(item).stream().filter(p -> !p.contains(".")).toList() + ")";
		}
		return "type=" + text(g, "type") + " coordinates=" + snippet(g.path("coordinates").toString(), 90);
	}

	static String featureInfo(JsonNode j) {
		if (j.has("features")) {
			JsonNode f = j.path("features");
			return " features=" + f.size() + (f.size() > 0 ? " properties=" + new TreeSet<>(f.get(0).path("properties").propertyNames())
					+ " geometry=" + text(f.get(0).path("geometry"), "type") : "");
		}
		if (j.has("result")) {
			JsonNode res = j.path("result");
			return " totalCount=" + text(j, "totalCount") + " result=" + res.size()
					+ (res.size() > 0 ? " keys=" + new TreeSet<>(res.get(0).propertyNames()) : "");
		}
		return "";
	}

	static String snippet(String s, int max) {
		String t = s.replaceAll("\\s+", " ");
		return t.length() > max ? t.substring(0, max) + "…" : t;
	}

}
