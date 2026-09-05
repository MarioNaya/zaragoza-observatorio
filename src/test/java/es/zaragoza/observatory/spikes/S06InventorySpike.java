package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.DATA_SPACE;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
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
import tools.jackson.databind.JsonNode;

/**
 * S0.6 — Inventario sistemático del catálogo (SPEC.md §3). Informe: docs/spikes/S0.6-inventario.md.
 * <p>
 * Primera pasada automática sobre los 436 datasets con las tres preguntas (territorial, temporal, vivo) y marcado de
 * los relacionados con inversión; segunda pasada: sondeo de los endpoints API de los candidatos de inversión para ver
 * si traen campos territoriales y temporales. La evaluación final es manual y se documenta en el informe.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S06InventorySpike {

	static final String ID = "S0.6-inventario";
	static final Pattern TERRITORIAL_WORDS = Pattern.compile(
			"barrio|junta|distrito|calle|direcci[oó]n|coordenad|geolocaliz|mapa|parcela|portal|secci[oó]n|ubicaci[oó]n|localizaci[oó]n|cartograf|callejero",
			Pattern.CASE_INSENSITIVE);
	static final Pattern GEO_FORMAT = Pattern.compile("ogc|geo|kml|shape|gml|wfs|wms", Pattern.CASE_INSENSITIVE);
	static final Pattern TEMPORAL_WORDS = Pattern.compile(
			"hist[oó]ric|serie|evoluci[oó]n|anual|mensual|diari|semanal|trimestr|tiempo real|20\\d\\d|19\\d\\d",
			Pattern.CASE_INSENSITIVE);
	static final Pattern INVESTMENT_WORDS = Pattern.compile(
			"presupuest|inversi[oó]n|obra|licencia|contrat|licitaci|adjudicaci|subvenci|participativ|plan de barrio|planes de barrio|equipamiento|gasto|factura|convenio|patrimonio|infraestructura|urbaniz|ejecuci[oó]n|pago a proveedores|deuda|ingreso",
			Pattern.CASE_INSENSITIVE);
	static final Pattern FIELD_TERRITORIAL = Pattern.compile(
			"barrio|junta|distrito|geometry|coordinates|direccion|calle|portal|\\blat\\b|\\blon\\b|\\bx\\b|\\by\\b|utm|address|location|codPos|postal",
			Pattern.CASE_INSENSITIVE);
	static final Pattern FIELD_TEMPORAL = Pattern.compile("fecha|date|anyo|año|year|ejercicio|inicio|fin|lastUpdated|pubDate|periodo",
			Pattern.CASE_INSENSITIVE);

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient();
	static List<JsonNode> catalog = new ArrayList<>();
	static Map<String, List<String>> swaggerPathsByTag = new TreeMap<>();
	static List<Row> matrix = new ArrayList<>();

	record Row(String id, String title, String theme, String periodicity, String modified, String geo, boolean territorial,
			String territorialWhy, boolean temporal, String temporalWhy, String alive, boolean hasApi, String apiTag,
			String formats, String investmentWords, boolean open, boolean explorable) {

		boolean candidate() {
			return territorial && temporal && !alive.startsWith("no");
		}

		List<String> cells() {
			return List.of(id, title.replace("|", "/"), theme, periodicity, modified, geo, territorial ? "sí" : "no", territorialWhy,
					temporal ? "sí" : "no", temporalWhy, alive, hasApi ? "sí" : "no", apiTag, formats, investmentWords, open ? "sí" : "no",
					explorable ? "sí" : "no", candidate() ? "sí" : "no");
		}

		static List<String> header() {
			return List.of("id", "título", "materia", "periodicidad", "modified", "geo", "territorial", "por qué", "temporal", "por qué",
					"vivo", "api", "tag swagger", "formatos", "palabras inversión", "abierto", "explorable", "candidato");
		}
	}

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
		var r = api.get(DATA_SPACE
				+ "/catalogo.json?rows=500&fl=id,title,description_basic,geo,geoname,spatialCoverageWKT,dcatSpatialReference,spatialRepresentationTypeCode,formato,accrualPeriodicity,modified,keywords,tag,materiaPrimaria,explorable,abierto,status");
		if (r.status() == 200 && r.isJson()) {
			r.json().path("result").forEach(catalog::add);
		}
		metric(ID, "catálogo cargado: " + catalog.size() + " datasets (" + r.status() + ")");
		var swagger = api.get(SEDE + "/catalogo/api.json");
		if (swagger.status() == 200 && swagger.isJson()) {
			swagger.json().path("paths").properties().forEach(p -> p.getValue().properties().forEach(op -> {
				if (op.getKey().equalsIgnoreCase("get")) {
					for (JsonNode t : op.getValue().path("tags")) {
						swaggerPathsByTag.computeIfAbsent(text(t), k -> new ArrayList<>()).add(p.getKey());
					}
				}
			}));
		}
		metric(ID, "swagger: tags con GET=" + swaggerPathsByTag.size());
	}

	@Test
	@Order(1)
	void buildMatrix() {
		heading(ID, "Matriz: tres preguntas sobre " + catalog.size() + " datasets");
		LocalDateTime now = LocalDateTime.now();
		for (JsonNode d : catalog) {
			String title = text(d, "title");
			String words = title + " " + text(d, "keywords") + " " + text(d, "tag");
			String description = text(d, "description_basic");
			var formats = new TreeSet<String>();
			String apiTag = "";
			boolean hasApi = false;
			for (JsonNode f : d.path("formato")) {
				String mt = text(f, "mediaType");
				formats.add(mt.replace("application/", "").replace("vnd.", ""));
				if ("application/api".equals(mt)) {
					hasApi = true;
					String tag = tagFromDocsUrl(text(f, "accessURL"));
					if (tag != null) {
						apiTag = tag;
					}
				}
				if (text(f, "accessURL").contains("/sede/servicio/")) {
					hasApi = true;
				}
			}
			// territorial
			List<String> why = new ArrayList<>();
			if ("S".equals(text(d, "geo"))) {
				why.add("geo=S");
			}
			Matcher m = TERRITORIAL_WORDS.matcher(words);
			if (m.find()) {
				why.add("palabra:" + m.group().toLowerCase());
			}
			if (formats.stream().anyMatch(f -> GEO_FORMAT.matcher(f).find())) {
				why.add("formato geo");
			}
			if (!text(d, "spatialRepresentationTypeCode").isEmpty()) {
				why.add("spatialRepresentationTypeCode");
			}
			boolean territorial = !why.isEmpty();
			// temporal
			List<String> whyT = new ArrayList<>();
			String periodicity = text(d, "accrualPeriodicity");
			Long periodDays = periodDays(periodicity);
			if (periodDays != null && periodDays > 0) {
				why(whyT, "periodicidad " + periodicity);
			}
			else if ("P0DT1S".equals(periodicity)) {
				why(whyT, "tiempo real");
			}
			Matcher mt = TEMPORAL_WORDS.matcher(words + " " + description);
			if (mt.find()) {
				why(whyT, "palabra:" + mt.group().toLowerCase());
			}
			boolean temporal = !whyT.isEmpty();
			// alive
			LocalDateTime modified = SpikeJson.date(d.path("modified"));
			String alive;
			if (modified == null) {
				alive = "no evaluable (sin modified)";
			}
			else if (periodDays != null && periodDays > 0) {
				double ratio = (double) ChronoUnit.DAYS.between(modified, now) / periodDays;
				alive = ratio <= 2 ? "sí (ratio " + String.format("%.1f", ratio) + ")" : "no (ratio " + String.format("%.1f", ratio) + ")";
			}
			else if ("P0DT1S".equals(periodicity)) {
				alive = "no evaluable (tiempo real)";
			}
			else {
				long days = ChronoUnit.DAYS.between(modified, now);
				alive = days <= 400 ? "sí (" + days + " d, sin periodicidad)" : "no (" + days + " d, sin periodicidad)";
			}
			Matcher mi = INVESTMENT_WORDS.matcher(words + " " + description);
			var inv = new TreeSet<String>();
			while (mi.find()) {
				inv.add(mi.group().toLowerCase());
			}
			matrix.add(new Row(text(d, "id"), title, text(d.path("materiaPrimaria"), "title"), periodicity.isEmpty() ? "-" : periodicity,
					modified == null ? "-" : modified.toLocalDate().toString(), text(d, "geo").isEmpty() ? "-" : text(d, "geo"), territorial,
					String.join(", ", why), temporal, String.join(", ", whyT), alive, hasApi, apiTag, String.join(" ", formats),
					String.join(" ", inv), "S".equals(text(d, "abierto")), "true".equals(text(d, "explorable"))));
		}
		Map<String, Integer> combos = new TreeMap<>();
		int candidates = 0, investment = 0, investmentCandidates = 0;
		for (Row r : matrix) {
			combos.merge("territorial=" + r.territorial() + " temporal=" + r.temporal() + " vivo=" + (r.alive().startsWith("sí") ? "sí"
					: r.alive().startsWith("no evaluable") ? "no evaluable" : "no"), 1, Integer::sum);
			candidates += r.candidate() ? 1 : 0;
			investment += r.investmentWords().isEmpty() ? 0 : 1;
			investmentCandidates += r.candidate() && !r.investmentWords().isEmpty() ? 1 : 0;
		}
		counts(ID, "combinación", combos);
		metric(ID, "candidatos (territorial ∧ temporal ∧ vivo o no evaluable)=" + candidates + " relacionados con inversión=" + investment
				+ " candidatos de inversión=" + investmentCandidates);
		Map<String, Integer> themes = new TreeMap<>();
		matrix.forEach(r -> themes.merge(r.theme().isEmpty() ? "(sin materia)" : r.theme(), 1, Integer::sum));
		counts(ID, "materiaPrimaria", themes);
		writeCsv();
		heading(ID, "Candidatos");
		table(ID, Row.header(), matrix.stream().filter(Row::candidate).map(Row::cells).toList());
		heading(ID, "Relacionados con inversión (todos, candidatos o no)");
		table(ID, Row.header(), matrix.stream().filter(r -> !r.investmentWords().isEmpty()).map(Row::cells).toList());
	}

	@Test
	@Order(2)
	void probeInvestmentApis() {
		heading(ID, "Sondeo de endpoints API de datasets relacionados con inversión o territorio (primer GET del tag)");
		int requests = 0;
		var seen = new TreeSet<String>();
		for (Row r : matrix) {
			if (r.apiTag().isEmpty() || !swaggerPathsByTag.containsKey(r.apiTag()) || (r.investmentWords().isEmpty() && !r.candidate())) {
				continue;
			}
			if (!seen.add(r.apiTag()) || requests >= 40) {
				continue;
			}
			String path = swaggerPathsByTag.get(r.apiTag()).stream().filter(p -> !p.contains("{")).sorted((a, b) -> a.length() - b.length())
					.findFirst().orElse(null);
			if (path == null) {
				continue;
			}
			requests++;
			probe(r.id() + " «" + r.title() + "» [" + r.apiTag() + "]", "https://www.zaragoza.es/sede" + path + ".json?rows=3&srsname=wgs84");
		}
		heading(ID, "Sondeo de endpoints conocidos por el Swagger sin dataset en el catálogo o clave para inversión");
		for (String path : List.of("/presupuesto/gasto-corriente", "/presupuesto/gasto-corriente/fecha", "/presupuesto/gastado-resumen",
				"/presupuesto/organo-resumen", "/presupuesto/programa-resumen", "/presupuesto/ingreso-corriente", "/licencia-obra",
				"/via-publica/incidencia", "/via-publica/incidencia/conservacion", "/equipamiento", "/registro-licencia", "/ideazgz/tipoFase",
				"/ayuda-subvencion", "/bienes-inmuebles", "/locales-vacios", "/inventario-emisiones/distritos")) {
			probe("swagger " + path, SEDE + path + ".json?rows=3&srsname=wgs84");
		}
		heading(ID, "Presupuestos participativos y planes de barrio en el catálogo (búsqueda textual)");
		Pattern pp = Pattern.compile("participativ|plan(es)? de barrio|plan integral|PICH|PIBO", Pattern.CASE_INSENSITIVE);
		int found = 0;
		for (JsonNode d : catalog) {
			String all = text(d, "title") + " " + text(d, "keywords") + " " + text(d, "tag") + " " + text(d, "description_basic");
			if (pp.matcher(all).find()) {
				found++;
				var urls = new ArrayList<String>();
				for (JsonNode f : d.path("formato")) {
					urls.add(text(f, "mediaType") + " " + text(f, "accessURL"));
				}
				metric(ID, "- id=" + text(d, "id") + " «" + text(d, "title") + "» periodicidad=" + text(d, "accrualPeriodicity") + " modified="
						+ text(d, "modified") + " geo=" + text(d, "geo") + " formatos=" + urls);
			}
		}
		metric(ID, "datasets que mencionan participativos / planes de barrio: " + found);
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	static void probe(String label, String url) {
		var r = api.get(url);
		List<JsonNode> items = new ArrayList<>();
		String shape = "-";
		if (r.status() == 200 && r.isJson()) {
			JsonNode n = r.json();
			JsonNode arr = n.isArray() ? n : n.has("result") ? n.path("result") : n.has("features") ? n.path("features") : n;
			shape = n.isArray() ? "array" : n.has("result") ? "{totalCount=" + text(n, "totalCount") + ",result}" : n.has("features") ? "FeatureCollection" : "objeto";
			if (arr.isArray()) {
				arr.forEach(items::add);
			}
			else if (arr.isObject()) {
				items.add(arr);
			}
		}
		var paths = items.isEmpty() ? new TreeSet<String>() : SpikeJson.paths(items.get(0));
		var territorial = paths.stream().filter(p -> FIELD_TERRITORIAL.matcher(p).find()).toList();
		var temporal = paths.stream().filter(p -> FIELD_TEMPORAL.matcher(p).find()).toList();
		metric(ID, "- " + label + " -> " + r.status() + " " + shape + " n=" + items.size() + " ms=" + r.elapsed().toMillis() + " url=" + url);
		metric(ID, "    campos territoriales=" + territorial + " temporales=" + temporal);
		metric(ID, "    todos=" + snippet(paths.toString(), 900));
		if (r.status() == 200 && r.isJson() && r.body().length() < 500_000) {
			SpikeFixtures.save("inventory", url.replace("https://www.zaragoza.es/", "").replaceAll("[^A-Za-z0-9._-]", "_") + ".json", r.body());
		}
	}

	static void writeCsv() {
		try {
			Files.createDirectories(SpikeFixtures.METRICS_ROOT);
			Path csv = SpikeFixtures.METRICS_ROOT.resolve("S0.6-inventario-matriz.csv");
			var lines = new ArrayList<String>();
			lines.add(String.join(";", Row.header()));
			for (Row r : matrix) {
				lines.add(r.cells().stream().map(c -> c.replace(";", ",").replace("\n", " ")).reduce((a, b) -> a + ";" + b).orElse(""));
			}
			Files.write(csv, lines, StandardCharsets.UTF_8);
			metric(ID, "matriz completa: " + csv + " (" + matrix.size() + " filas)");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static void why(List<String> list, String reason) {
		list.add(reason);
	}

	static Long periodDays(String p) {
		if (p == null || !p.startsWith("P")) {
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

	static String snippet(String s, int max) {
		String t = s.replaceAll("\\s+", " ");
		return t.length() > max ? t.substring(0, max) + "…" : t;
	}

	static Map<String, Integer> ordered() {
		return new LinkedHashMap<>();
	}

	static LocalDate today() {
		return LocalDate.now();
	}

}
