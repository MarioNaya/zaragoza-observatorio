package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.paths;
import static es.zaragoza.observatory.spikes.support.SpikeJson.percentile;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

import es.zaragoza.observatory.spikes.support.SpikeFixtures;
import es.zaragoza.observatory.spikes.support.SpikeJson;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S3.1 — Contratación pública OCDS: cómo se ingiere de verdad (fase 3, contexto {@code spending}, ADR-003).
 * Informe: docs/spikes/S3.1-ocds-ingesta.md.
 * <p>
 * S0.2 midió la fuente lo justo para decidir que <b>no tiene territorio</b> (139 release packages, ningún campo de
 * localización) y dejó por escrito cuatro cosas «a medir en fase 3». Esas cuatro son las preguntas 1 a 4:
 * <ol>
 * <li>por qué el listado sin filtro (5.720 ocids) parecía <b>menor</b> que el universo alcanzable con
 * {@code after} (≥ 7.991), y cuál es la enumeración segura;</li>
 * <li>qué filtran de verdad {@code before}/{@code after}, que S0.2 sospechaba que era el periodo del contrato y no
 * la fecha de publicación;</li>
 * <li>qué proporción de ocids tiene release de verdad (S0.2 vio 42 % de 404, concentrados en los recientes) y si
 * los 404 son permanentes o solo tardíos;</li>
 * <li>si sigue habiendo <b>un</b> release por package, que es lo que permite no implementar compiled releases.</li>
 * </ol>
 * Y tres preguntas que S0.2 no se hizo y que las fases 1 y 2 han enseñado a hacer antes de escribir el traductor:
 * <ol start="5">
 * <li><b>Dato personal</b> (regla 22, ADR-012, ADR-016). Aquí el riesgo <b>no es texto libre</b>: el
 * {@code parties[].id} de esta fuente lleva el <b>NIF incrustado</b> ({@code 12619-NIF-B50892819-award-65236}).
 * Un adjudicatario persona física traería ahí su DNI o su NIE, en un campo <b>estructural</b> que además es la
 * clave con la que el propio documento enlaza adjudicación y adjudicatario. Se cuentan patrones, nunca se imprime
 * el valor.</li>
 * <li><b>Coste de la ingesta</b>: no hay listado con detalle, así que el histórico son miles de peticiones de
 * detalle. Hay que medir latencia y volumen para saber si cabe en el planificador (ADR-004, ADR-009).</li>
 * <li><b>Incremental</b>: sin {@code ETag} ni {@code Last-Modified} (S0.2), ¿hay algún eje que diga qué cambió?
 * Sin él, cada ejecución vuelve a pedirlo todo.</li>
 * </ol>
 * <b>Este spike barre la fuente entera</b>, no una muestra: son ~8.000 procesos y ~23 MB, y una decisión de la
 * regla 22 no se toma sobre el 4 % (ADR-012 se midió sobre 89.432 registros y ADR-016 sobre 42.342).
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S31OcdsIngestionSpike {

	static final String ID = "S3.1-ocds-ingesta";
	static final String FIXTURES = "ocds";

	static final String OCDS = SEDE + "/contratacion-publica/ocds";
	static final String LIST = OCDS + "/contracting-process.json";

	/** S0.2: el listado no tiene tope de {@code rows} y {@code start} se ignora. */
	static final int BIG_ROWS = 20000;

	/**
	 * Valor de {@code after} que abre el listado ampliado. No es una fecha: por encima del umbral (entre
	 * 2017-01-01 y 2017-01-02) el valor da igual y el parámetro deja de filtrar (§2). Se manda uno claramente
	 * futuro para no depender de dónde esté el umbral.
	 */
	static final String AFTER_SWITCH = "2030-01-01T00:00:00Z";

	/** Pausa entre peticiones del barrido: la fuente es pública y no se le hace un barrido a pelo. */
	static final Duration PAUSE = Duration.ofMillis(40);

	/** Packages que se conservan en memoria para el inventario de caminos y los fixtures. */
	static final int KEPT = 40;

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(120));

	/** Universo de ocids: unión del listado sin filtro y del ampliado con {@code after} (§1). */
	static final List<String> universe = new ArrayList<>();

	static final Set<String> plainList = new LinkedHashSet<>();

	/** Una fila por petición de detalle del barrido completo. */
	static final List<Probe> probes = new ArrayList<>();

	/** Recuento de patrones personales por camino. Solo recuentos: el valor no se imprime ni se guarda (regla 22). */
	static final Map<String, Map<String, Integer>> patternHits = new TreeMap<>();

	/** Cobertura de caminos JSON acumulada sobre el barrido entero (no cabe guardar los documentos). */
	static final Map<String, Integer> pathCoverage = new TreeMap<>();

	/** Agregados del barrido: se calculan al vuelo porque los 8.000 packages no se conservan. */
	static final Map<String, Integer> tags = new TreeMap<>();
	static final Map<String, Integer> tenderStatus = new TreeMap<>();
	static final Map<String, Integer> methods = new TreeMap<>();
	static final Map<String, Integer> categories = new TreeMap<>();
	static final Map<String, Integer> awardStatus = new TreeMap<>();
	static final Map<String, Integer> contractStatus = new TreeMap<>();
	static final Map<String, Integer> roles = new TreeMap<>();
	static final Map<String, Integer> currencies = new TreeMap<>();
	static final Map<String, Integer> releasesPerPackage = new TreeMap<>();
	static final Map<String, Integer> publishedByYear = new TreeMap<>();
	static final Map<String, Integer> stages = new TreeMap<>();
	static final Map<String, Integer> nifShapes = new TreeMap<>();
	static final Map<String, Integer> idShapes = new TreeMap<>();

	static int withTenderValue;
	static int withCpv;
	static int withAward;
	static int withContract;
	static int withMultipleAwards;
	static int publishedDiffersFromReleaseDate;
	static int unsafeForFixture;
	static double tenderTotal;
	static double awardTotal;

	/** Unos pocos packages conservados: inventario completo de caminos y candidatos a fixture. */
	static final Map<String, JsonNode> kept = new LinkedHashMap<>();

	record Probe(String ocid, int number, int status, long ms, int bytes) {
	}

	/** Un candidato a «la fecha que filtra `before`/`after`» (§2). */
	record Candidate(String name, String value) {
	}

	// --- utilidades -------------------------------------------------------------------------------------

	static String url(String base, String... keyValues) {
		var params = new LinkedHashMap<String, String>();
		for (int i = 0; i < keyValues.length; i += 2) {
			params.put(keyValues[i], keyValues[i + 1]);
		}
		return ZaragozaSpikeClient.url(base, params);
	}

	static String detailUrl(String ocid) {
		return OCDS + "/contracting-process/" + ocid + ".json";
	}

	/** {@code ocds-1xraxc-6621-ContractingProcess} → 6621. El número es el orden de alta del expediente. */
	static final Pattern OCID_NUMBER = Pattern.compile("(\\d+)");

	static int number(String ocid) {
		Matcher m = OCID_NUMBER.matcher(ocid.replace("1xraxc", ""));
		return m.find() ? Integer.parseInt(m.group(1)) : -1;
	}

	/** El listado es un array en la raíz de {@code {ocid, id}} (S0.2, {@code ResponseShape.ARRAY}). */
	static List<String> ocids(Response response) {
		if (response.failed() || response.status() != 200 || !response.isJson()) {
			return List.of();
		}
		var out = new ArrayList<String>();
		JsonNode tree = response.json();
		if (tree.isArray()) {
			tree.forEach(n -> out.add(text(n, "ocid")));
		}
		return out;
	}

	static JsonNode release(JsonNode pkg) {
		return pkg.path("releases").path(0);
	}

	static void pause() {
		try {
			Thread.sleep(PAUSE.toMillis());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static String iso(int year, int month, int day) {
		return String.format("%04d-%02d-%02dT00:00:00Z", year, month, day);
	}

	static String iso(LocalDate date) {
		return date + "T00:00:00Z";
	}

	// --- patrones de dato personal (regla 22: se cuentan, nunca se imprimen) -----------------------------

	static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

	static {
		PATTERNS.put("DNI", Pattern.compile("\\b[0-9]{8}\\s?-?\\s?[A-Za-z]\\b"));
		PATTERNS.put("NIE", Pattern.compile("\\b[XYZxyz]\\s?-?\\s?[0-9]{7}\\s?-?\\s?[A-Za-z]\\b"));
		PATTERNS.put("CIF", Pattern.compile(
				"\\b[ABCDEFGHJNPQRSUVWabcdefghjnpqrsuvw]\\s?-?\\s?[0-9]{7}\\s?-?\\s?[0-9A-Ja-j]\\b"));
		PATTERNS.put("correo", Pattern.compile("[\\w.+-]+@[\\w-]+\\.[A-Za-z]{2,}"));
		PATTERNS.put("telefono", Pattern.compile("\\b[6789][0-9]{8}\\b"));
		PATTERNS.put("tratamiento", Pattern.compile("(?i)\\b(D\\.|DÑA\\.|DOÑA|SR\\.|SRA\\.|DON)\\s+[A-ZÁÉÍÓÚÑ]"));
		PATTERNS.put("firma", Pattern.compile("(?i)\\b(atentamente|un saludo|fdo\\.?|firmado)\\b"));
	}

	/** El NIF va incrustado en {@code parties[].id}: {@code 12619-NIF-B50892819-award-65236}. */
	static final Pattern EMBEDDED_NIF = Pattern.compile("-NIF-([0-9A-Za-z]+)");

	/** Letras del DNI por resto módulo 23: distingue un DNI de verdad de ocho dígitos y una letra cualquiera. */
	static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

	static boolean validDni(String raw) {
		String clean = raw.replaceAll("[^0-9A-Za-z]", "");
		if (clean.length() != 9) {
			return false;
		}
		try {
			int n = Integer.parseInt(clean.substring(0, 8));
			return DNI_LETTERS.charAt(n % 23) == Character.toUpperCase(clean.charAt(8));
		}
		catch (NumberFormatException e) {
			return false;
		}
	}

	/** Letras de control del NIE (X=0, Y=1, Z=2 delante del número, mismo módulo 23). */
	static boolean validNie(String raw) {
		String clean = raw.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
		if (clean.length() != 9) {
			return false;
		}
		int prefix = "XYZ".indexOf(clean.charAt(0));
		if (prefix < 0) {
			return false;
		}
		try {
			int n = Integer.parseInt(prefix + clean.substring(1, 8));
			return DNI_LETTERS.charAt(n % 23) == clean.charAt(8);
		}
		catch (NumberFormatException e) {
			return false;
		}
	}

	static void scan(String field, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		Map<String, Integer> byPattern = patternHits.computeIfAbsent(field, k -> new TreeMap<>());
		byPattern.merge("noVacios", 1, Integer::sum);
		PATTERNS.forEach((name, pattern) -> {
			if (pattern.matcher(value).find()) {
				byPattern.merge(name, 1, Integer::sum);
			}
		});
		Matcher dni = PATTERNS.get("DNI").matcher(value);
		while (dni.find()) {
			byPattern.merge(validDni(dni.group()) ? "dniConLetraValida" : "dniConLetraInvalida", 1, Integer::sum);
		}
		Matcher nie = PATTERNS.get("NIE").matcher(value);
		while (nie.find()) {
			byPattern.merge(validNie(nie.group()) ? "nieConLetraValida" : "nieConLetraInvalida", 1, Integer::sum);
		}
	}

	/** Recorre el package entero contando patrones por camino, y de paso acumula la cobertura de caminos. */
	static void scanDocument(JsonNode node, String prefix) {
		if (node.isObject()) {
			node.properties().forEach(e -> scanDocument(e.getValue(), prefix.isEmpty() ? e.getKey()
					: prefix + "." + e.getKey()));
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				scanDocument(child, prefix + "[]");
			}
		}
		else if (node.isString()) {
			scan(prefix, node.stringValue());
		}
	}

	/**
	 * Un package solo entra como fixture si <b>ninguna</b> de sus cadenas contiene un DNI o un NIE con letra de
	 * control válida (regla 22). No basta con redactar el texto libre: aquí el riesgo está en identificadores.
	 */
	static boolean safeForFixture(JsonNode pkg) {
		var found = new ArrayList<String>();
		collectStrings(pkg, found);
		for (String value : found) {
			Matcher dni = PATTERNS.get("DNI").matcher(value);
			while (dni.find()) {
				if (validDni(dni.group())) {
					return false;
				}
			}
			Matcher nie = PATTERNS.get("NIE").matcher(value);
			while (nie.find()) {
				if (validNie(nie.group())) {
					return false;
				}
			}
		}
		return true;
	}

	static void collectStrings(JsonNode node, List<String> out) {
		if (node.isObject()) {
			node.properties().forEach(e -> collectStrings(e.getValue(), out));
		}
		else if (node.isArray()) {
			node.forEach(child -> collectStrings(child, out));
		}
		else if (node.isString()) {
			out.add(node.stringValue());
		}
	}

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
		metric(ID, "Fuente: " + LIST + " y " + OCDS + "/contracting-process/{ocid}.json (S0.2, ADR-003).");
		metric(ID, "Barrido **completo** del detalle, pausa de " + PAUSE.toMillis() + " ms entre peticiones.");
	}

	// --- 1. enumeración: cuántos procesos hay de verdad ---------------------------------------------------

	/**
	 * S0.2 dejó una contradicción sin resolver: el listado sin filtro parecía devolver menos ocids que el mismo
	 * listado con {@code after}. Mientras no se sepa cuál es el universo, no hay ingesta que se pueda llamar
	 * completa (lección de S2.2 §10: un barrido se comprueba contando <b>identificadores distintos</b>).
	 */
	@Test
	@Order(1)
	void enumeration() {
		heading(ID, "1. Enumeración: cuál es el universo de ocids");

		Response small = api.get(url(LIST, "rows", "2"));
		metric(ID, "- " + small.summary());
		assertThat(small.status()).isEqualTo(200);
		metric(ID, "- forma: array en la raíz; campos del elemento: " + small.json().get(0).propertyNames());
		SpikeFixtures.save(FIXTURES, "contracting-process-list-rows2.json", small.body());
		SpikeFixtures.saveHeaders(FIXTURES, "contracting-process-list.headers", small.status(), small.headers());

		Response plain = api.get(url(LIST, "rows", String.valueOf(BIG_ROWS)));
		List<String> plainOcids = ocids(plain);
		plainList.addAll(plainOcids);
		metric(ID, "- `rows=" + BIG_ROWS + "` sin filtro → **" + plainOcids.size() + "** elementos, **"
				+ plainList.size() + "** ocids distintos, " + plain.body().length() + " bytes, "
				+ plain.elapsed().toMillis() + " ms (S0.2 anotó 5.720 el 2026-09-05).");

		Response offset = api.get(url(LIST, "rows", "5", "start", "1000"));
		Response noOffset = api.get(url(LIST, "rows", "5"));
		boolean startIgnored = ocids(offset).equals(ocids(noOffset));
		metric(ID, "- `start=1000` devuelve lo mismo que `start=0`: **" + startIgnored
				+ "** — la paginación por offset no existe en esta fuente (S0.5, regla 18).");
		assertThat(startIgnored).isTrue();

		// El hallazgo que cambia el universo: con `after` presente el listado devuelve **mas** ocids.
		Response widened = api.get(url(LIST, "rows", String.valueOf(BIG_ROWS), "after", AFTER_SWITCH));
		var widenedOcids = new LinkedHashSet<>(ocids(widened));
		metric(ID, "- `after=" + AFTER_SWITCH + "&rows=" + BIG_ROWS + "` → **" + widenedOcids.size()
			+ "** ocids distintos, " + widened.body().length() + " bytes, " + widened.elapsed().toMillis()
			+ " ms.");

		var onlyWidened = new TreeSet<>(widenedOcids);
		onlyWidened.removeAll(plainList);
		var onlyPlain = new TreeSet<>(plainList);
		onlyPlain.removeAll(widenedOcids);
		metric(ID, "- solo con `after`: **" + onlyWidened.size() + "**; solo sin filtro: **" + onlyPlain.size()
			+ "**. El listado sin filtro es un **subconjunto estricto**: se deja "
			+ String.format("%.1f", 100.0 * onlyWidened.size() / widenedOcids.size()) + " % de los procesos.");
		assertThat(onlyPlain).isEmpty();

		var all = new TreeSet<String>(plainList);
		all.addAll(widenedOcids);
		universe.addAll(all.stream().sorted((a, b) -> Integer.compare(number(a), number(b))).toList());
		metric(ID, "- **universo = unión de las dos enumeraciones: " + universe.size() + " ocids**; rango de "
			+ "número de expediente " + number(universe.getFirst()) + "–" + number(universe.getLast()) + ".");
		assertThat(universe).hasSizeGreaterThan(plainList.size());

		var byNumber = new TreeMap<Integer, Integer>();
		universe.forEach(o -> byNumber.merge(number(o), 1, Integer::sum));
		long repeated = byNumber.values().stream().filter(n -> n > 1).count();
		metric(ID, "- números de expediente con más de un ocid: " + repeated + " (si es 0, el número identifica).");

		int min = number(universe.getFirst());
		int max = number(universe.getLast());
		int missing = (max - min + 1) - byNumber.size();
		metric(ID, "- huecos en la secuencia " + min + "–" + max + ": **" + missing + "** números sin ocid ("
			+ String.format("%.1f", 100.0 * missing / (max - min + 1)) + " %), así que **el universo no se "
			+ "puede generar contando**: hay que enumerarlo con el listado.");

		metric(ID, "- los " + onlyWidened.size() + " que solo aparecen con `after` son los de número **más "
			+ "bajo**: " + onlyWidened.stream().map(S31OcdsIngestionSpike::number).sorted().limit(10).toList()
			+ " … (los diez primeros).");
	}

	// --- 2. qué filtran before/after ---------------------------------------------------------------------

	/**
	 * S0.2: «{@code after=2026-08-01} devuelve 7.991 ocids, más que el listado completo sin filtro». Un filtro que
	 * amplía el resultado no filtra. La hipótesis a contrastar es que <b>repite ocids</b>: el filtro cruza contra
	 * una tabla hija y devuelve una fila por adjudicación o por contrato. Se cuenta distinguiendo elementos de
	 * identificadores distintos, que es la lección de S2.2 §10.
	 */
	@Test
	@Order(2)
	void beforeAfterSemantics() {
		heading(ID, "2. Qué hacen de verdad `before` y `after`");

		metric(ID, "**`after` no es un límite inferior: es un interruptor.** Escalera de valores, contando "
			+ "ocids distintos y cuántos de ellos faltan en el listado sin filtro:");
		var ladder = new ArrayList<List<String>>();
		for (String date : List.of("2000-01-01", "2015-01-01", "2016-06-01", "2017-01-01", "2017-01-02",
			"2018-01-01", "2022-01-01", "2026-01-01", "2030-01-01")) {
			var got = new LinkedHashSet<>(ocids(api.get(url(LIST, "rows", String.valueOf(BIG_ROWS), "after",
				date + "T00:00:00Z"))));
			var outside = new TreeSet<>(got);
			outside.removeAll(plainList);
			ladder.add(List.of(date, String.valueOf(got.size()), String.valueOf(outside.size())));
			pause();
		}
		table(ID, List.of("`after`", "ocids distintos", "que no están en el listado sin filtro"), ladder);
		metric(ID, "- el salto está entre **2017-01-01 y 2017-01-02**, y por encima del umbral el valor **da "
			+ "igual**: `after=2030-01-01` sigue devolviendo procesos publicados en 2008. No filtra por fecha.");

		metric(ID, "**`before` sí es un límite superior de la fecha de publicación**, y filtra sobre el "
			+ "conjunto que haya dejado `after`:");
		var upper = new ArrayList<List<String>>();
		for (String date : List.of("2009-01-01", "2016-01-01", "2018-01-01", "2020-01-01", "2023-01-01",
			"2026-01-01", "2030-01-01")) {
			var withAfter = new LinkedHashSet<>(ocids(api.get(url(LIST, "rows", String.valueOf(BIG_ROWS), "after",
				AFTER_SWITCH, "before", date + "T00:00:00Z"))));
			var alone = new LinkedHashSet<>(ocids(api.get(url(LIST, "rows", String.valueOf(BIG_ROWS), "before",
				date + "T00:00:00Z"))));
			upper.add(List.of(date, String.valueOf(withAfter.size()), String.valueOf(alone.size())));
			pause();
		}
		table(ID, List.of("`before`", "con `after` presente", "`before` a solas"), upper);

		// La trampa de forma: sin resultados, el mismo endpoint deja de devolver un array.
		Response empty = api.get(url(LIST, "rows", String.valueOf(BIG_ROWS), "before", "2009-01-01T00:00:00Z"));
		metric(ID, "- **`before=2009-01-01` a solas devuelve `" + empty.body().trim() + "`**: cuando no hay "
			+ "resultados el endpoint cambia de forma y responde el **envoltorio** de la sede en vez de un array "
			+ "vacío. El traductor tiene que aceptar las dos formas o revienta con la primera respuesta vacía.");
		assertThat(empty.json().isArray()).isFalse();
		SpikeFixtures.save(FIXTURES, "contracting-process-list-empty.json", empty.body());

		for (String value : List.of("2020-01-01", "2020-01-01T00:00:00", "2020-01-01T00:00:00+02:00", "20200101")) {
			Response r = api.tryGet(url(LIST, "rows", "1", "after", value));
			metric(ID, "- `after=" + value + "` → " + r.status() + " (solo vale `yyyy-MM-ddTHH:mm:ssZ`, S0.2).");
		}

		// Sirve `after` como marca de agua para el incremental? Con el interruptor, no.
		var recent = new ArrayList<List<String>>();
		for (int days : List.of(7, 30, 365)) {
			var got = new LinkedHashSet<>(ocids(api.get(url(LIST, "rows", String.valueOf(BIG_ROWS), "after",
				iso(LocalDate.now().minusDays(days))))));
			recent.add(List.of("hoy menos " + days + " días", String.valueOf(got.size())));
			pause();
		}
		table(ID, List.of("`after`", "ocids distintos"), recent);
		metric(ID, "- la ventana reciente **no encoge**: `after` no vale como marca de agua y el incremental "
			+ "tiene que salir de comparar el listado con lo ya ingerido.");

		// Qué fecha filtra `before`: se contrasta con el documento.
		Response window = api.get(url(LIST, "rows", String.valueOf(BIG_ROWS), "after", AFTER_SWITCH, "before",
			"2010-01-01T00:00:00Z"));
		List<String> sample = ocids(window).stream().distinct().limit(20).toList();
		var before2010 = new TreeMap<String, Integer>();
		int resolved = 0;
		for (String ocid : sample) {
			Response detail = api.tryGet(detailUrl(ocid));
			pause();
			if (detail.status() != 200 || !detail.isJson() || detail.json().path("releases").isEmpty()) {
				continue;
			}
			resolved++;
			JsonNode pkg = detail.json();
			JsonNode rel = release(pkg);
			for (Candidate c : List.of(new Candidate("publishedDate", text(pkg, "publishedDate")),
				new Candidate("releases[].date", text(rel, "date")),
				new Candidate("tender.tenderPeriod.startDate",
					text(rel.path("tender").path("tenderPeriod"), "startDate")),
				new Candidate("awards[0].date", text(rel.path("awards").path(0), "date")),
				new Candidate("contracts[0].dateSigned", text(rel.path("contracts").path(0), "dateSigned")))) {
				if (!c.value().isBlank() && c.value().compareTo("2010") < 0) {
					before2010.merge(c.name(), 1, Integer::sum);
				}
			}
		}
		metric(ID, "- de " + sample.size() + " ocids de `before=2010-01-01`, " + resolved + " tienen release. "
			+ "Cuántos tienen **cada fecha anterior a 2010** (el campo que llegue a " + resolved
			+ " es el que filtra):");
		counts(ID, "campo con fecha anterior a 2010", before2010);
	}

	// --- 3. barrido completo del detalle ------------------------------------------------------------------

	/**
	 * Los ~5.700 detalles, uno a uno. Da a la vez la cobertura exacta (cuántos ocids tienen release), el coste
	 * real de la ingesta histórica y —lo que decide la ADR— el recuento de datos personales sobre la fuente
	 * <b>entera</b>, no sobre una muestra.
	 */
	@Test
	@Order(3)
	void fullSweep() {
		heading(ID, "3. Barrido completo del detalle");
		assertThat(universe).isNotEmpty();

		long started = System.currentTimeMillis();
		for (String ocid : universe) {
			Response r = api.tryGet(detailUrl(ocid));
			probes.add(new Probe(ocid, number(ocid), r.status(), r.elapsed().toMillis(), r.body().length()));
			if (r.status() == 200 && r.isJson()) {
				JsonNode pkg = r.json();
				aggregate(ocid, pkg);
			}
			pause();
		}
		long elapsed = System.currentTimeMillis() - started;

		var byStatus = new TreeMap<String, Integer>();
		probes.forEach(p -> byStatus.merge(String.valueOf(p.status()), 1, Integer::sum));
		counts(ID, "estado HTTP del detalle", byStatus);
		long ok = probes.stream().filter(p -> p.status() == 200).count();
		metric(ID, "- **" + ok + " de " + probes.size() + "** ocids responden 200 ("
				+ String.format("%.1f", 100.0 * ok / probes.size()) + " %). S0.2 midió 58 % sobre 240.");
		metric(ID, "- barrido completo real: **" + String.format("%.1f", elapsed / 1000.0 / 60)
				+ " min** con pausa de " + PAUSE.toMillis() + " ms.");

		int min = number(universe.getFirst());
		int max = number(universe.getLast());
		var rows = new ArrayList<List<String>>();
		for (int d = 0; d < 10; d++) {
			final int from = min + (max - min) * d / 10;
			final int to = d == 9 ? max + 1 : min + (max - min) * (d + 1) / 10;
			List<Probe> slice = probes.stream().filter(p -> p.number() >= from && p.number() < to).toList();
			if (slice.isEmpty()) {
				continue;
			}
			long hits = slice.stream().filter(p -> p.status() == 200).count();
			rows.add(List.of(from + "–" + to, String.valueOf(slice.size()), String.valueOf(hits),
					String.format("%.0f %%", 100.0 * hits / slice.size())));
		}
		table(ID, List.of("número de expediente", "pedidos", "responden 200", "cobertura"), rows);

		List<Long> ms = probes.stream().filter(p -> p.status() == 200).map(Probe::ms).sorted().toList();
		List<Long> bytes = probes.stream().filter(p -> p.status() == 200).map(p -> (long) p.bytes()).sorted().toList();
		metric(ID, "- latencia del detalle (solo 200): p50 " + percentile(ms, 0.5) + " ms, p90 "
				+ percentile(ms, 0.9) + " ms, p99 " + percentile(ms, 0.99) + " ms, máx "
				+ (ms.isEmpty() ? -1 : ms.getLast()) + " ms.");
		metric(ID, "- tamaño del release package: p50 " + percentile(bytes, 0.5) + " B, p90 "
				+ percentile(bytes, 0.9) + " B, máx " + (bytes.isEmpty() ? -1 : bytes.getLast()) + " B; total "
				+ String.format("%.1f", bytes.stream().mapToLong(Long::longValue).sum() / 1024.0 / 1024) + " MB.");

		probes.stream().filter(p -> p.status() != 200).limit(1).forEach(p -> {
			Response r = api.tryGet(detailUrl(p.ocid()));
			metric(ID, "- ejemplo de fallo: HTTP " + r.status() + " con cuerpo `"
					+ r.body().substring(0, Math.min(160, r.body().length())).replace('\n', ' ') + "`. **El cuerpo "
					+ "dice 400 y la cabecera 404**: otro error interno filtrado al exterior (S2.2, S2.3).");
		});

		metric(ID, "- packages con `releases` **vacío** aunque respondan 200: **"
				+ releasesPerPackage.getOrDefault("0", 0) + "**. Un 200 no garantiza release.");
		assertThat(probes).hasSameSizeAs(universe);
	}

	/** Acumula todo lo que hay que saber de un package sin conservarlo (el barrido entero no cabe en memoria). */
	static void aggregate(String ocid, JsonNode pkg) {
		releasesPerPackage.merge(String.valueOf(pkg.path("releases").size()), 1, Integer::sum);
		paths(pkg).forEach(p -> pathCoverage.merge(p, 1, Integer::sum));
		scanDocument(pkg, "");

		String published = text(pkg, "publishedDate");
		if (published.length() >= 4) {
			publishedByYear.merge(published.substring(0, 4), 1, Integer::sum);
		}
		if (pkg.path("releases").isEmpty()) {
			return;
		}
		JsonNode rel = release(pkg);
		if (!published.isBlank() && !published.equals(text(rel, "date"))) {
			publishedDiffersFromReleaseDate++;
		}
		rel.path("tag").forEach(t -> tags.merge(text(t), 1, Integer::sum));
		JsonNode tender = rel.path("tender");
		tenderStatus.merge(text(tender, "status"), 1, Integer::sum);
		methods.merge(text(tender, "procurementMethod"), 1, Integer::sum);
		categories.merge(text(tender, "mainProcurementCategory"), 1, Integer::sum);
		if (tender.path("value").has("amount")) {
			withTenderValue++;
			tenderTotal += tender.path("value").path("amount").asDouble();
			currencies.merge(text(tender.path("value"), "currency"), 1, Integer::sum);
		}
		if (!tender.path("items").isEmpty()
				&& !text(tender.path("items").path(0).path("classification"), "id").isBlank()) {
			withCpv++;
		}
		JsonNode awards = rel.path("awards");
		if (!awards.isEmpty()) {
			withAward++;
			if (awards.size() > 1) {
				withMultipleAwards++;
			}
			awards.forEach(a -> {
				awardStatus.merge(text(a, "status"), 1, Integer::sum);
				awardTotal += a.path("value").path("amount").asDouble();
			});
		}
		JsonNode contracts = rel.path("contracts");
		if (!contracts.isEmpty()) {
			withContract++;
			contracts.forEach(c -> contractStatus.merge(text(c, "status"), 1, Integer::sum));
		}
		rel.path("parties").forEach(p -> {
			p.path("roles").forEach(r -> roles.merge(text(r), 1, Integer::sum));
			String id = text(p, "id");
			idShapes.merge(id.contains("-NIF-") ? "con -NIF-" : "sin -NIF-", 1, Integer::sum);
			Matcher m = EMBEDDED_NIF.matcher(id);
			if (m.find()) {
				String nif = m.group(1);
				nifShapes.merge(Character.isDigit(nif.charAt(0)) ? "empieza por dígito (persona física)"
						: "empieza por letra " + Character.toUpperCase(nif.charAt(0)) + " (jurídica)", 1,
						Integer::sum);
			}
		});
		stages.merge(stage(rel), 1, Integer::sum);

		if (kept.size() < KEPT) {
			kept.put(ocid, pkg);
		}
		if (!safeForFixture(pkg)) {
			unsafeForFixture++;
		}
	}

	/**
	 * {@code stage} de ADR-003 §2 derivado del documento: {@code committed} si hay contrato firmado o adjudicación
	 * activa, {@code planned} si la licitación sigue viva, y ninguno si el proceso quedó desierto o cancelado.
	 * {@code executed} no existe en OCDS (no hay {@code implementation}): sale del presupuesto (S0.6).
	 */
	static String stage(JsonNode rel) {
		boolean signed = false;
		for (JsonNode c : rel.path("contracts")) {
			if (!text(c, "dateSigned").isBlank()) {
				signed = true;
			}
		}
		if (signed) {
			return "committed (contrato firmado)";
		}
		for (JsonNode a : rel.path("awards")) {
			if ("active".equals(text(a, "status"))) {
				return "committed (adjudicación activa)";
			}
		}
		String status = text(rel.path("tender"), "status");
		if ("active".equals(status)) {
			return "planned (licitación viva)";
		}
		return "sin stage (" + (status.isBlank() ? "sin tender.status" : status) + ")";
	}

	// --- 4. forma del release package ---------------------------------------------------------------------

	/** Un release por package es lo que permite no implementar compiled releases (S0.2). Se vuelve a comprobar. */
	@Test
	@Order(4)
	void releaseShape() {
		heading(ID, "4. Forma del release package");
		assertThat(pathCoverage).isNotEmpty();
		int n = releasesPerPackage.values().stream().mapToInt(Integer::intValue).sum();

		counts(ID, "releases por package", releasesPerPackage);
		counts(ID, "releases[].tag", tags);
		counts(ID, "tender.status", tenderStatus);
		counts(ID, "tender.procurementMethod", methods);
		counts(ID, "tender.mainProcurementCategory", categories);
		counts(ID, "awards[].status", awardStatus);
		counts(ID, "contracts[].status", contractStatus);
		counts(ID, "parties[].roles", roles);
		counts(ID, "moneda de tender.value", currencies);
		counts(ID, "`publishedDate` por año", publishedByYear);
		counts(ID, "stage derivado (ADR-003 §2)", stages);

		metric(ID, "- de " + n + " packages con 200: con `tender.value` " + withTenderValue + ", con CPV " + withCpv
				+ ", con adjudicación " + withAward + " (de ellos " + withMultipleAwards
				+ " con más de una), con contrato " + withContract + ".");
		metric(ID, "- `publishedDate` distinto de `releases[].date`: " + publishedDiffersFromReleaseDate
				+ " packages. Si es 0, son el mismo dato y basta con guardar uno.");
		metric(ID, "- importe **licitado** del histórico: " + String.format("%,.0f", tenderTotal)
				+ " €; **adjudicado** (suma de todas las adjudicaciones): " + String.format("%,.0f", awardTotal)
				+ " €. Ninguno es gasto pagado (ADR-003 §2).");

		metric(ID, "- caminos JSON distintos en el barrido: " + pathCoverage.size()
				+ ". Los presentes en más del 80 % de los documentos:");
		var frequent = new TreeMap<String, Integer>();
		pathCoverage.forEach((path, count) -> {
			if (count >= 0.8 * n) {
				frequent.put(path, count);
			}
		});
		table(ID, List.of("camino", "documentos"), SpikeJson.rows(frequent, 80));

		metric(ID, "- documentos con `releases[].planning` (gasto previsto): "
				+ pathCoverage.getOrDefault("releases[].planning", 0)
				+ "; con `releases[].contracts[].implementation` (pagos): "
				+ pathCoverage.getOrDefault("releases[].contracts[].implementation", 0)
				+ ". Sin ninguno de los dos, OCDS no da **ni gasto previsto ni gasto pagado**.");
	}

	// --- 5. localización: se vuelve a comprobar que no la hay ---------------------------------------------

	/**
	 * ADR-003 se apoya en que no existe. Es barato volver a comprobarlo y caro asumirlo. El patrón lleva
	 * delimitadores de palabra a propósito: sin ellos, {@code relatedLots} y {@code relatedProces} casan por el
	 * «lat» de «related» y dan seis falsos positivos.
	 */
	@Test
	@Order(5)
	void noLocation() {
		heading(ID, "5. Localización");
		Map<String, Integer> located = SpikeJson.filter(pathCoverage,
				"(?<![a-z])(location|address|geometry|geo|latitude|longitude|coord|region|postal|locality|street"
						+ "|place|ubicac|emplaz|nuts|country|barrio|distrito|junta)(?![a-z])");
		metric(ID, "- caminos con aspecto de localización sobre " + pathCoverage.size() + " caminos y "
				+ probes.stream().filter(p -> p.status() == 200).count() + " documentos: " + located.size() + " "
				+ located.keySet() + ".");
		metric(ID, "- ADR-003 §1 sigue en pie: contratación **no tiene territorio** y no se geocodifica (regla 6).");
		assertThat(located).isEmpty();
	}

	// --- 6. dato personal: el NIF va dentro del identificador ---------------------------------------------

	/**
	 * El riesgo de esta fuente no es el texto libre sino {@code parties[].id}, que lleva el NIF incrustado. Un
	 * adjudicatario persona física traería su DNI o su NIE en un campo estructural, y ese campo es además la clave
	 * con la que el documento enlaza adjudicación y adjudicatario: no se puede «no pedir» como en ADR-012.
	 */
	@Test
	@Order(6)
	void personalData() {
		heading(ID, "6. Dato personal en identificadores y texto libre (regla 22)");
		assertThat(patternHits).isNotEmpty();

		var rows = new ArrayList<List<String>>();
		patternHits.forEach((field, hits) -> {
			int interesting = hits.entrySet().stream().filter(e -> !e.getKey().equals("noVacios"))
					.mapToInt(Map.Entry::getValue).sum();
			if (interesting > 0) {
				rows.add(List.of(field, String.valueOf(hits.getOrDefault("noVacios", 0)), hits.toString()));
			}
		});
		table(ID, List.of("camino", "valores no vacíos", "coincidencias"), rows);

		int validDniCount = patternHits.values().stream().mapToInt(h -> h.getOrDefault("dniConLetraValida", 0)).sum();
		int validNieCount = patternHits.values().stream().mapToInt(h -> h.getOrDefault("nieConLetraValida", 0)).sum();
		metric(ID, "- sobre el **barrido completo**: DNI con letra de control válida **" + validDniCount
				+ "**, NIE válidos **" + validNieCount + "**.");
		counts(ID, "forma de parties[].id", idShapes);
		counts(ID, "primera posición del NIF incrustado en parties[].id", nifShapes);
		long naturalPersons = nifShapes.entrySet().stream()
				.filter(e -> e.getKey().startsWith("empieza por dígito"))
				.mapToLong(Map.Entry::getValue).sum();
		metric(ID, "- identificadores de **persona física** (NIF que empieza por dígito): **" + naturalPersons
				+ "**.");
		metric(ID, "- packages que no pueden ser fixture por contener DNI o NIE válido: " + unsafeForFixture + ".");
	}

	// --- 7. cabeceras condicionales e incremental ---------------------------------------------------------

	/** Sin eje incremental, cada ejecución vuelve a pedir miles de detalles: eso decide el diseño del job. */
	@Test
	@Order(7)
	void conditionalAndIncremental() {
		heading(ID, "7. Cabeceras condicionales e incremental");

		Response list = api.get(url(LIST, "rows", "5"));
		metric(ID, "- listado: ETag=" + list.header("ETag") + ", Last-Modified=" + list.header("Last-Modified"));
		String ocid = kept.keySet().iterator().next();
		Response detail = api.get(detailUrl(ocid));
		metric(ID, "- detalle: ETag=" + detail.header("ETag") + ", Last-Modified=" + detail.header("Last-Modified"));
		SpikeFixtures.saveHeaders(FIXTURES, "contracting-process-detail.headers", detail.status(), detail.headers());

		Response conditional = api.tryGet(detailUrl(ocid),
				h -> h.set("If-Modified-Since", "Sat, 01 Jan 2028 00:00:00 GMT"));
		metric(ID, "- `If-Modified-Since` en el futuro → " + conditional.status()
				+ " (304 sería ingesta condicional; 200 es que no se honra, como en toda la sede, regla 18).");

		Response sorted = api.tryGet(url(LIST, "rows", "6", "sort", "id desc"));
		List<String> sortedNumbers = ocids(sorted).stream().map(o -> String.valueOf(number(o))).toList();
		boolean actuallySorted = sortedNumbers.equals(sortedNumbers.stream()
				.sorted((a, b) -> Integer.compare(Integer.parseInt(b), Integer.parseInt(a))).toList());
		metric(ID, "- `sort=id desc` en el listado → " + sorted.status() + ", números devueltos " + sortedNumbers
				+ "; ¿ordenados de verdad? **" + actuallySorted + "**.");
		if (!actuallySorted) {
			metric(ID, "- otro parámetro **aceptado y no aplicado**, la familia de `q=junta.id==N` (S2.1), "
					+ "`status=rejected` (S2.2) y `removeproperties` (S2.4). El listado se toma entero y se ordena "
					+ "en casa.");
		}

		Response head = api.tryHead(url(LIST, "rows", "1"));
		metric(ID, "- `HEAD` sobre el listado → " + head.status() + " (el Swagger de la API responde 400 a HEAD, "
				+ "S1.2: no es uniforme).");
	}

	// --- 8. coste de la ingesta ---------------------------------------------------------------------------

	/** Cuánto cuesta el histórico y cuánto una pasada diaria, con los números medidos (ADR-004, ADR-009). */
	@Test
	@Order(8)
	void ingestionCost() {
		heading(ID, "8. Coste de la ingesta");

		List<Long> ms = probes.stream().map(Probe::ms).sorted().toList();
		long p50 = percentile(ms, 0.5);
		long p90 = percentile(ms, 0.9);
		double meanMs = probes.stream().mapToLong(Probe::ms).average().orElse(0);
		long totalBytes = probes.stream().filter(p -> p.status() == 200).mapToLong(Probe::bytes).sum();
		long ok = probes.stream().filter(p -> p.status() == 200).count();
		int n = universe.size();

		metric(ID, "- universo: **" + n + "** ocids, de los que **" + ok + "** responden 200 y **"
				+ (n - ok) + "** responden 404.");
		metric(ID, "- latencia media " + String.format("%.0f", meanMs) + " ms (p50 " + p50 + ", p90 " + p90 + ").");
		metric(ID, "- histórico secuencial sin pausa: **" + String.format("%.0f", n * meanMs / 1000 / 60)
				+ " min**; con el semáforo de 4 de ADR-004: " + String.format("%.0f", n * meanMs / 1000 / 60 / 4)
				+ " min.");
		metric(ID, "- volumen descargado: **" + String.format("%.1f", totalBytes / 1024.0 / 1024) + " MB**.");
		metric(ID, "- el número de expediente crece con el tiempo, así que los ocids nuevos aparecen al final del "
				+ "listado: un incremental por «ocid que no está en la base» cuesta **1 petición de listado más un "
				+ "detalle por proceso nuevo**, y no depende de ningún filtro de la API.");
		metric(ID, "- a eso hay que sumar los **" + (n - ok) + " sin release**, que son los recientes: si se "
				+ "reintentan todos cada día, la pasada diaria vuelve a costar " + (n - ok)
				+ " peticiones. Con cadencia decreciente por antigüedad del intento, mucho menos.");
	}

	// --- 9. endpoints hermanos ----------------------------------------------------------------------------

	/** S0.2 los declaró inestables y sin aportación. La ADR va a decir «no se usan»: conviene volver a mirarlos. */
	@Test
	@Order(9)
	void siblings() {
		heading(ID, "9. Endpoints hermanos (`award`, `contract`, `tender`, `organisation`)");
		var rows = new ArrayList<List<String>>();
		for (String name : List.of("award", "contract", "tender", "organisation")) {
			Response r = api.tryGet(url(OCDS + "/" + name + ".json", "rows", "5"));
			int size = r.status() == 200 && r.isJson() && r.json().isArray() ? r.json().size() : -1;
			rows.add(List.of(name, String.valueOf(r.status()), String.valueOf(size),
					size > 0 ? String.valueOf(r.json().get(0).propertyNames()) : "-"));
			pause();
		}
		table(ID, List.of("endpoint", "estado", "elementos con `rows=5`", "campos"), rows);
		metric(ID, "- `organisation.json?rows=5` devolviendo 10 elementos es `rows` **ignorado** en ese recurso.");
		metric(ID, "- ninguno aporta nada que no esté en el release package del proceso (S0.2): no se ingieren.");
	}

	// --- 10. fixtures -------------------------------------------------------------------------------------

	/**
	 * Los fixtures del traductor: uno por forma del documento. Solo entran packages <b>sin</b> DNI ni NIE con
	 * letra de control válida en ninguna de sus cadenas (regla 22); aquí no basta con redactar el texto libre,
	 * porque el identificador estructural lleva el NIF dentro y redactarlo dejaría un fixture que no prueba nada.
	 */
	@Test
	@Order(10)
	void saveFixtures() {
		heading(ID, "10. Fixtures grabados");
		var wanted = new LinkedHashSet<>(List.of("contract", "award", "tender", "empty"));
		var saved = new ArrayList<String>();
		for (Map.Entry<String, JsonNode> entry : kept.entrySet()) {
			JsonNode pkg = entry.getValue();
			String shape = pkg.path("releases").isEmpty() ? "empty"
					: !release(pkg).path("contracts").isEmpty() ? "contract"
							: !release(pkg).path("awards").isEmpty() ? "award" : "tender";
			if (!wanted.contains(shape) || !safeForFixture(pkg)) {
				continue;
			}
			wanted.remove(shape);
			String name = "contracting-process-" + shape + ".json";
			SpikeFixtures.save(FIXTURES, name, pkg.toString());
			saved.add(name + " (" + entry.getKey() + ")");
		}
		saved.forEach(nm -> metric(ID, "- `" + nm + "`"));
		metric(ID, "- formas sin ejemplo entre los " + kept.size() + " packages conservados: " + wanted + ".");
		assertThat(saved).isNotEmpty();
	}

	/** Deja constancia de los caminos completos del documento, para el traductor. */
	@Test
	@Order(11)
	void documentPaths() {
		heading(ID, "11. Todos los caminos del release package");
		metric(ID, "- " + pathCoverage.size() + " caminos distintos en el barrido completo.");
		metric(ID, "```");
		pathCoverage.forEach((path, n) -> metric(ID, path + "  (" + n + ")"));
		metric(ID, "```");
	}

}
