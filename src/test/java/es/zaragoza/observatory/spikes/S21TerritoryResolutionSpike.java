package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.enc;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
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
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.Response;
import tools.jackson.databind.JsonNode;

/**
 * S2.1 — Resolución dirección/punto → junta (docs/ESTADO.md §4, fase 2 paso 1).
 * Informe: docs/spikes/S2.1-resolucion-territorial.md.
 * <p>
 * Es la llave del eje territorial entero (SPEC.md §1): si asignar una junta a cada registro no funciona,
 * degradan a la vez las quejas, la actividad urbana y todos los cruces posteriores. S0.4 y S0.6 dejaron
 * escrito que «la API resuelve dirección → junta a través del recurso {@code portal}», pero eso **nunca se
 * comprobó**: es justo lo que este spike verifica.
 * <p>
 * Preguntas:
 * <ol>
 * <li>¿Resuelve la API una dirección a junta, con qué endpoint y con qué garantías? ¿Y un punto?</li>
 * <li>¿Qué numeración usa cada fuente? Correspondencia {@code distrito.id} ↔ {@code idpadron} (SPEC.md §9).</li>
 * <li>¿Basta con los 29 polígonos y una prueba punto-en-polígono? Se contrasta contra las 3.824 fichas de
 * {@code locales-vacios}, que ya traen su junta resuelta por el ayuntamiento.</li>
 * <li>¿Qué porcentaje de cada fuente territorial queda sin asignar?</li>
 * </ol>
 * La geometría se resuelve aquí con un ray casting propio de precisión suficiente para medir: en producción
 * la resolución es {@code ST_Contains} en PostGIS (S0.4 recomendación 4). Lo que se mide es si la vía
 * geométrica coincide con la fuente oficial, no la implementación.
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S21TerritoryResolutionSpike {

	static final String ID = "S2.1-resolucion-territorial";
	static final String FIXTURES = "geo";

	/** Tope de página de la sede (S0.5, regla 18). */
	static final int ROWS = 500;

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient(Duration.ofSeconds(120));

	/** Las 29 juntas con su polígono en WGS84, por {@code distrito.id}. */
	static final Map<Integer, Junta> juntas = new LinkedHashMap<>();

	/** Nombre normalizado de cada junta → id, para casar los títulos que publica cada fuente. */
	static final Map<String, Integer> juntaPorNombre = new LinkedHashMap<>();

	// --- geometría mínima -------------------------------------------------------------------------------

	/** Un anillo de coordenadas (lon, lat) en WGS84. */
	record Ring(double[] lons, double[] lats) {

		boolean contains(double lon, double lat) {
			boolean inside = false;
			int n = lons.length;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				double yi = lats[i], yj = lats[j];
				if ((yi > lat) != (yj > lat)
						&& lon < (lons[j] - lons[i]) * (lat - yi) / (yj - yi) + lons[i]) {
					inside = !inside;
				}
			}
			return inside;
		}
	}

	/** Un polígono: anillo exterior y sus agujeros. */
	record Part(Ring outer, List<Ring> holes) {

		boolean contains(double lon, double lat) {
			if (!outer.contains(lon, lat)) {
				return false;
			}
			return holes.stream().noneMatch(hole -> hole.contains(lon, lat));
		}
	}

	record Junta(int id, String title, List<Part> parts, double minLon, double minLat, double maxLon,
			double maxLat, int vertices) {

		boolean contains(double lon, double lat) {
			if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) {
				return false;
			}
			return parts.stream().anyMatch(part -> part.contains(lon, lat));
		}
	}

	static Ring ring(JsonNode coordinates) {
		int n = coordinates.size();
		double[] lons = new double[n];
		double[] lats = new double[n];
		for (int i = 0; i < n; i++) {
			lons[i] = coordinates.get(i).get(0).asDouble();
			lats[i] = coordinates.get(i).get(1).asDouble();
		}
		return new Ring(lons, lats);
	}

	static Part part(JsonNode polygon) {
		Ring outer = ring(polygon.get(0));
		List<Ring> holes = new ArrayList<>();
		for (int i = 1; i < polygon.size(); i++) {
			holes.add(ring(polygon.get(i)));
		}
		return new Part(outer, holes);
	}

	static Junta junta(JsonNode node) {
		JsonNode geometry = node.path("geometry");
		String type = text(geometry, "type");
		JsonNode coordinates = geometry.path("coordinates");
		List<Part> parts = new ArrayList<>();
		if ("Polygon".equals(type)) {
			parts.add(part(coordinates));
		}
		else if ("MultiPolygon".equals(type)) {
			for (JsonNode polygon : coordinates) {
				parts.add(part(polygon));
			}
		}
		double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
		double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
		int vertices = 0;
		for (Part p : parts) {
			for (Ring r : allRings(p)) {
				vertices += r.lons().length;
				for (int i = 0; i < r.lons().length; i++) {
					minLon = Math.min(minLon, r.lons()[i]);
					maxLon = Math.max(maxLon, r.lons()[i]);
					minLat = Math.min(minLat, r.lats()[i]);
					maxLat = Math.max(maxLat, r.lats()[i]);
				}
			}
		}
		return new Junta(node.path("id").asInt(), text(node, "title"), parts, minLon, minLat, maxLon, maxLat,
				vertices);
	}

	static List<Ring> allRings(Part part) {
		List<Ring> rings = new ArrayList<>();
		rings.add(part.outer());
		rings.addAll(part.holes());
		return rings;
	}

	/** Juntas que contienen el punto. Más de una revela solape entre polígonos. */
	static List<Integer> locate(double lon, double lat) {
		List<Integer> hits = new ArrayList<>();
		for (Junta j : juntas.values()) {
			if (j.contains(lon, lat)) {
				hits.add(j.id());
			}
		}
		return hits;
	}

	// --- carga ------------------------------------------------------------------------------------------

	@BeforeAll
	static void loadJuntas() {
		SpikeFixtures.startMetrics(ID);
		heading(ID, "Juntas y geometría (`distrito.json?srsname=wgs84`)");
		Response r = api.get(SEDE + "/distrito.json?srsname=wgs84&rows=100");
		metric(ID, r.summary());
		assertThat(r.status()).isEqualTo(200);
		SpikeFixtures.save(FIXTURES, "distrito.json_srsname-wgs84_rows-100", r.body());
		for (JsonNode node : r.json().path("result")) {
			Junta j = junta(node);
			juntas.put(j.id(), j);
			juntaPorNombre.put(clave(j.title()), j.id());
		}
		metric(ID, "juntas=" + juntas.size() + " vértices=" + juntas.values().stream().mapToInt(Junta::vertices).sum()
				+ " multiparte=" + juntas.values().stream().filter(j -> j.parts().size() > 1).count()
				+ " con agujeros=" + juntas.values().stream()
						.filter(j -> j.parts().stream().anyMatch(p -> !p.holes().isEmpty())).count());
		assertThat(juntas).hasSize(29);
	}

	// --- 1. numeraciones --------------------------------------------------------------------------------

	/**
	 * SPEC.md §9 dejó abierto si {@code distrito.id} (1–30 con huecos) es el mismo número que el
	 * {@code idpadron} de los datasets SOCIO24. La respuesta está dentro de {@code distrito/{id}.indicadores}.
	 */
	@Test
	@Order(1)
	void correspondenciaEntreNumeraciones() {
		heading(ID, "Correspondencia `distrito.id` ↔ `idpadron` (`distrito/{id}.json`)");
		List<List<String>> rows = new ArrayList<>();
		Map<String, Integer> aniosPorJunta = new TreeMap<>();
		int iguales = 0, distintos = 0, sinIndicadores = 0, idPadronInestable = 0;
		for (Junta j : juntas.values()) {
			Response r = api.tryGet(SEDE + "/distrito/" + j.id() + ".json?fl=id,title,indicadores");
			if (r.status() != 200) {
				metric(ID, "junta " + j.id() + " -> " + r.status() + " " + r.body().substring(0,
						Math.min(120, r.body().length())));
				continue;
			}
			if (j.id() == 6) {
				// El Rabal: fixture del detalle, que es de donde salen `idpadron` y el padrón (lo usa `geo`).
				SpikeFixtures.save(FIXTURES, "distrito-6-indicadores.json", r.body());
			}
			JsonNode indicadores = r.json().path("indicadores");
			if (indicadores.size() == 0) {
				sinIndicadores++;
				rows.add(List.of(String.valueOf(j.id()), j.title(), "—", "—", "—", "—", "sin indicadores"));
				continue;
			}
			Set<String> padronIds = new TreeSet<>();
			Set<String> anios = new TreeSet<>();
			JsonNode ultimo = null;
			for (JsonNode i : indicadores) {
				padronIds.add(text(i, "idpadron"));
				anios.add(text(i, "anyo"));
				if (ultimo == null || text(i, "anyo").compareTo(text(ultimo, "anyo")) > 0) {
					ultimo = i;
				}
			}
			aniosPorJunta.merge(String.join(",", anios), 1, Integer::sum);
			if (padronIds.size() > 1) {
				idPadronInestable++;
			}
			int idDatosAbiertos = ultimo.path("iddatosab").asInt(-1);
			if (idDatosAbiertos == j.id()) {
				iguales++;
			}
			else {
				distintos++;
			}
			rows.add(List.of(String.valueOf(j.id()), j.title(), String.valueOf(idDatosAbiertos),
					String.join("/", padronIds), text(ultimo, "nombre"), text(ultimo, "tipo"),
					text(ultimo, "anyo") + ": " + text(ultimo, "totpob") + " hab, " + text(ultimo, "km2") + " km²"));
		}
		table(ID, List.of("distrito.id", "title", "iddatosab", "idpadron", "nombre", "tipo", "último padrón"), rows);
		metric(ID, "iddatosab == distrito.id en " + iguales + " juntas, distinto en " + distintos + ", sin indicadores "
				+ sinIndicadores + ", idpadron inestable entre años " + idPadronInestable);
		counts(ID, "años con indicadores", aniosPorJunta);
		assertThat(distintos).isZero();
	}

	// --- 2. dirección → junta ---------------------------------------------------------------------------

	/**
	 * El recurso {@code portalero/v2} es el callejero de portales: lo que S0.4 llamó «el ayuntamiento resuelve
	 * dirección → junta por el recurso portal». Aquí se mide qué garantiza de verdad.
	 */
	@Test
	@Order(2)
	void busquedaDeDireccionEnElCallejero() {
		heading(ID, "`portalero/v2/list.json`: búsqueda por dirección");
		record Probe(String what, String query) {
		}
		List<Probe> probes = List.of(
				new Probe("sin filtro", "rows=3"),
				new Probe("dirección exacta", "rows=3&fl=id,direccion,junta,codPos&direccion=" + enc("ALFONSO I 39")),
				new Probe("dirección con tipo de vía", "rows=3&fl=id,direccion,junta&direccion=" + enc("CALLE ALFONSO I, 39")),
				new Probe("calle inexistente, número existente", "rows=3&fl=id,direccion,junta&direccion=" + enc("CALLE INEXISTENTE 999")),
				new Probe("calle y número inexistentes", "rows=3&fl=id,direccion,junta&direccion=" + enc("XXXXXXXX YYYYYYYY")),
				new Probe("calle + número por separado", "rows=3&fl=id,direccion,junta&calle.title=" + enc("CALLE ALFONSO I") + "&numero=39"),
				new Probe("titleContains", "rows=3&fl=id,direccion,junta&calle.titleContains=" + enc("ALFONSO")),
				new Probe("filtro por junta (FIQL)", "rows=3&fl=id,direccion,junta&q=" + enc("junta.id==6")),
				new Probe("filtro por junta inexistente (FIQL)", "rows=3&fl=id,direccion,junta&q=" + enc("junta.id==99")),
				new Probe("filtro por junta por título", "rows=3&fl=id,direccion,junta&junta.title=" + enc("DELICIAS")));
		List<List<String>> rows = new ArrayList<>();
		for (Probe p : probes) {
			Response r = api.tryGet(SEDE + "/portalero/v2/list.json?" + p.query());
			JsonNode result = r.status() == 200 && r.isJson() ? r.json().path("result") : null;
			int n = result == null ? -1 : result.size();
			String first = n > 0 ? text(result.get(0), "direccion") : "—";
			String junta = n > 0 ? text(result.get(0).path("junta"), "title") : "—";
			String juntaId = n > 0 ? text(result.get(0).path("junta"), "id") : "—";
			rows.add(List.of(p.what(), String.valueOf(r.status()),
					n < 0 ? "sin JSON" : (result.isMissingNode() ? "sin `result`" : String.valueOf(n)),
					first.isBlank() ? "—" : first, junta.isBlank() ? "—" : junta,
					juntaId.isBlank() ? "(ausente)" : juntaId,
					r.status() == 200 && r.isJson() ? text(r.json(), "totalCount") : "—"));
			if (p.what().startsWith("dirección exacta")) {
				SpikeFixtures.save(FIXTURES, "portalero-direccion-alfonso-i-39.json", r.body());
				SpikeFixtures.saveHeaders(FIXTURES, "portalero-direccion-alfonso-i-39.headers", r.status(),
						r.headers());
			}
			if (p.what().startsWith("calle inexistente")) {
				SpikeFixtures.save(FIXTURES, "portalero-direccion-inexistente.json", r.body());
			}
			pause();
		}
		table(ID, List.of("consulta", "status", "devueltos", "1ª dirección", "1ª junta", "junta.id", "totalCount"),
				rows);
	}

	/**
	 * ¿Sirve la respuesta para decidir? La pregunta útil no es si la cadena de la dirección coincide —el
	 * callejero normaliza «AVDA.» a «AVENIDA»— sino si <b>la junta que devuelve es la correcta</b>. Se usan
	 * direcciones reales de {@code locales-vacios}, que traen la junta que el ayuntamiento asigna al portal.
	 */
	@Test
	@Order(3)
	void fiabilidadDeLaBusquedaPorDireccion() {
		heading(ID, "Fiabilidad: la junta que devuelve la búsqueda por dirección");
		Response sample = api.get(SEDE + "/locales-vacios.json?rows=" + ROWS
				+ "&srsname=wgs84&fl=id,streetAddress,numero,portal.junta");
		List<List<String>> rows = new ArrayList<>();
		int probadas = 0, juntaCorrecta = 0, juntaIncorrecta = 0, juntaSinCasar = 0, vacios = 0;
		Set<String> calleVista = new TreeSet<>();
		for (JsonNode local : sample.json().path("result")) {
			String calle = text(local, "streetAddress");
			String numero = text(local, "numero");
			int juntaOficial = local.path("portal").path("junta").path("id").asInt(-1);
			if (calle.isBlank() || numero.isBlank() || juntaOficial <= 0 || !calleVista.add(calle)) {
				continue;
			}
			if (probadas++ >= 30) {
				break;
			}
			String consulta = calle + " " + numero;
			Response r = api.tryGet(SEDE + "/portalero/v2/list.json?rows=1&fl=id,direccion,junta&direccion="
					+ enc(consulta));
			JsonNode first = r.status() == 200 && r.isJson() ? r.json().path("result").path(0) : null;
			String devuelta = first == null ? "" : text(first, "direccion");
			String juntaTexto = first == null ? "" : text(first.path("junta"), "title");
			Integer juntaDevuelta = juntaPorNombre.get(clave(juntaTexto));
			String veredicto;
			if (devuelta.isBlank()) {
				vacios++;
				veredicto = "sin resultado";
			}
			else if (juntaDevuelta == null) {
				juntaSinCasar++;
				veredicto = "junta ilegible";
			}
			else if (juntaDevuelta == juntaOficial) {
				juntaCorrecta++;
				veredicto = "correcta";
			}
			else {
				juntaIncorrecta++;
				veredicto = "**incorrecta**";
			}
			rows.add(List.of(consulta, devuelta.isBlank() ? "—" : devuelta,
					juntaTexto.isBlank() ? "—" : juntaTexto, nombre(juntaOficial), veredicto));
			pause();
		}
		table(ID, List.of("consulta", "dirección devuelta", "junta devuelta", "junta oficial del portal", "veredicto"),
				rows);
		metric(ID, "direcciones probadas=" + rows.size() + " junta correcta=" + juntaCorrecta + " junta incorrecta="
				+ juntaIncorrecta + " junta ilegible=" + juntaSinCasar + " sin resultado=" + vacios);
		if (!rows.isEmpty()) {
			metric(ID, String.format(Locale.ROOT, "acierto de la búsqueda por dirección = %.1f %%",
					100.0 * juntaCorrecta / rows.size()));
		}
	}

	/**
	 * Los títulos de junta que publica el callejero llegan con la tilde rota ({@code CASCO HISTÓRICO}).
	 * Se mide cuántos de los 29 nombres oficiales son reconocibles en esa forma.
	 */
	@Test
	@Order(4)
	void legibilidadDeLosTitulosDeJunta() {
		heading(ID, "Títulos de junta en `portalero/v2`: ¿se pueden casar con las 29 oficiales?");
		Map<String, Integer> vistos = new TreeMap<>();
		for (int start = 0; start < 2000; start += ROWS) {
			Response r = api.tryGet(SEDE + "/portalero/v2/list.json?rows=" + ROWS + "&start=" + start
					+ "&fl=id,junta&calle.titleContains=" + enc("A"));
			if (r.status() != 200 || !r.isJson()) {
				break;
			}
			JsonNode result = r.json().path("result");
			if (result.size() == 0) {
				break;
			}
			for (JsonNode portal : result) {
				vistos.merge(text(portal.path("junta"), "title"), 1, Integer::sum);
			}
			if (result.size() < ROWS) {
				break;
			}
			pause();
		}
		List<List<String>> rows = new ArrayList<>();
		int casados = 0, sinCasar = 0;
		for (Map.Entry<String, Integer> e : vistos.entrySet()) {
			Integer id = juntaPorNombre.get(clave(e.getKey()));
			if (id == null) {
				sinCasar += e.getValue();
			}
			else {
				casados += e.getValue();
			}
			rows.add(List.of(e.getKey().isBlank() ? "(vacío)" : e.getKey(),
					escapado(e.getKey()) ? "sí" : "no", String.valueOf(e.getValue()),
					id == null ? "**no casa**" : nombre(id)));
		}
		table(ID, List.of("junta.title del callejero", "carácter no imprimible", "portales", "junta oficial"), rows);
		metric(ID, "títulos distintos=" + vistos.size() + " portales con junta reconocible=" + casados
				+ " sin reconocer=" + sinCasar);
	}

	// --- 3. punto → junta por API -----------------------------------------------------------------------

	/** El Swagger documenta {@code point} y {@code distance} en dos recursos. ¿Filtran de verdad? */
	@Test
	@Order(5)
	void consultaEspacialDeclaradaEnElSwagger() {
		heading(ID, "`point` + `distance`: ¿existe consulta espacial?");
		// Dos puntos reales muy separados (Casco Histórico y Garrapinillos), en UTM30N y en WGS84.
		record Probe(String what, String url) {
		}
		List<Probe> probes = List.of(
				new Probe("portalero, punto A en utm30n", SEDE + "/portalero/v2/list.json?rows=1&fl=id,direccion,junta&point=676655,4613922&distance=50"),
				new Probe("portalero, punto B en utm30n", SEDE + "/portalero/v2/list.json?rows=1&fl=id,direccion,junta&point=664995,4617895&distance=50"),
				new Probe("portalero, punto A en wgs84", SEDE + "/portalero/v2/list.json?rows=1&fl=id,direccion,junta&srsname=wgs84&point=-0.8796861,41.6556244&distance=50"),
				new Probe("portalero, punto A lat,lon", SEDE + "/portalero/v2/list.json?rows=1&fl=id,direccion,junta&srsname=wgs84&point=41.6556244,-0.8796861&distance=50"),
				new Probe("portalero, punto A radio 5 km", SEDE + "/portalero/v2/list.json?rows=1&fl=id,direccion,junta&point=676655,4613922&distance=5000"),
				new Probe("portalero, punto sin distance", SEDE + "/portalero/v2/list.json?rows=1&fl=id,direccion,junta&point=676655,4613922"),
				new Probe("registro-licencia/portal, punto A", SEDE + "/registro-licencia/portal.json?rows=1&point=676655,4613922&distance=50"),
				new Probe("registro-licencia/portal, sin punto", SEDE + "/registro-licencia/portal.json?rows=1"));
		List<List<String>> rows = new ArrayList<>();
		for (Probe p : probes) {
			Response r = api.tryGet(p.url());
			JsonNode json = r.status() == 200 && r.isJson() ? r.json() : null;
			JsonNode first = json == null ? null : json.path("result").path(0);
			String id = first == null ? "—" : text(first, "id");
			String direccion = first == null ? "—" : text(first, "direccion");
			String cuerpo = json == null ? r.body().substring(0, Math.min(90, r.body().length())) : "";
			rows.add(List.of(p.what(), String.valueOf(r.status()),
					json == null ? "—" : String.valueOf(json.path("result").size()),
					id.isBlank() ? "—" : id, direccion.isBlank() ? "—" : direccion, cuerpo));
			pause();
		}
		table(ID, List.of("consulta", "status", "devueltos", "1º id", "1ª dirección", "cuerpo si no es JSON"), rows);
	}

	// --- 4. punto → junta por geometría -----------------------------------------------------------------

	/**
	 * La prueba de fuego: {@code locales-vacios} trae a la vez el punto y la junta que el ayuntamiento le
	 * asigna. Si la resolución geométrica coincide, la vía PostGIS está probada contra la fuente oficial.
	 */
	@Test
	@Order(7)
	void resolucionGeometricaContraLaJuntaOficial() {
		heading(ID, "Punto → junta por geometría, contra `locales-vacios.portal.junta`");
		int total = 0, conPunto = 0, conJunta = 0, comparables = 0, coinciden = 0, discrepan = 0, fuera = 0,
				solape = 0;
		Map<String, Integer> discrepancias = new TreeMap<>();
		List<List<String>> ejemplos = new ArrayList<>();
		for (int start = 0; start < 4000; start += ROWS) {
			Response r = api.get(SEDE + "/locales-vacios.json?rows=" + ROWS + "&start=" + start
					+ "&srsname=wgs84&fl=id,portal.junta,geometry");
			if (start == 0) {
				metric(ID, r.summary());
				SpikeFixtures.save(FIXTURES, "locales-vacios-junta-punto-page0.json", r.body());
			}
			JsonNode result = r.json().path("result");
			if (result.size() == 0) {
				break;
			}
			for (JsonNode local : result) {
				total++;
				JsonNode geometry = local.path("geometry");
				int juntaOficial = local.path("portal").path("junta").path("id").asInt(-1);
				boolean punto = "Point".equals(text(geometry, "type"));
				if (punto) {
					conPunto++;
				}
				if (juntaOficial > 0) {
					conJunta++;
				}
				if (!punto || juntaOficial <= 0) {
					continue;
				}
				comparables++;
				double lon = geometry.path("coordinates").get(0).asDouble();
				double lat = geometry.path("coordinates").get(1).asDouble();
				List<Integer> hits = locate(lon, lat);
				if (hits.size() > 1) {
					solape++;
				}
				if (hits.isEmpty()) {
					fuera++;
					if (ejemplos.size() < 15) {
						ejemplos.add(List.of(text(local, "id"), nombre(juntaOficial), "(ninguna)",
								coord(lon) + ", " + coord(lat)));
					}
				}
				else if (hits.contains(juntaOficial)) {
					coinciden++;
				}
				else {
					discrepan++;
					discrepancias.merge(nombre(juntaOficial) + " → " + nombre(hits.get(0)), 1, Integer::sum);
					if (ejemplos.size() < 15) {
						ejemplos.add(List.of(text(local, "id"), nombre(juntaOficial), nombre(hits.get(0)),
								coord(lon) + ", " + coord(lat)));
					}
				}
			}
			if (result.size() < ROWS) {
				break;
			}
		}
		metric(ID, "fichas=" + total + " con punto=" + conPunto + " con junta oficial=" + conJunta + " comparables="
				+ comparables);
		metric(ID, "coinciden=" + coinciden + " discrepan=" + discrepan + " punto fuera de las 29 juntas=" + fuera
				+ " punto en más de una junta=" + solape);
		if (comparables > 0) {
			metric(ID, String.format(Locale.ROOT, "acuerdo = %.2f %%", 100.0 * coinciden / comparables));
		}
		if (!discrepancias.isEmpty()) {
			counts(ID, "discrepancia (oficial → geometría)", discrepancias);
		}
		if (!ejemplos.isEmpty()) {
			table(ID, List.of("id local", "junta oficial", "junta por geometría", "punto (lon, lat)"), ejemplos);
		}
		assertThat(comparables).isPositive();
	}

	// --- 5. señal territorial por fuente ----------------------------------------------------------------

	/**
	 * Cuánto de cada fuente candidata de la fase 2 es asignable a una junta, y por qué vía. El punto no
	 * siempre está en la raíz del registro ({@code portal.geometry}, {@code edificio.geometry}) y la junta
	 * propia unas veces es un id y otras un texto: se buscan todos los caminos conocidos.
	 */
	@Test
	@Order(8)
	void senalTerritorialPorFuente() {
		heading(ID, "Señal territorial por fuente (muestra de " + ROWS + ")");
		record Fuente(String nombre, String url, List<String> geometrias, List<String> juntas) {
		}
		List<String> raiz = List.of("geometry");
		List<Fuente> fuentes = List.of(
				new Fuente("quejas-sugerencias (500 más recientes)",
						SEDE + "/quejas-sugerencias/list.json?rows=" + ROWS + "&srsname=wgs84&sort="
								+ enc("requested_datetime desc")
								+ "&fl=service_request_id,geometry,district,address_string,requested_datetime",
						raiz, List.of("district")),
				new Fuente("locales-vacios",
						SEDE + "/locales-vacios.json?rows=" + ROWS + "&srsname=wgs84&fl=id,portal.junta,geometry",
						List.of("geometry", "portal.geometry"), List.of("portal.junta.id")),
				new Fuente("licencia-obra (parcelas)",
						SEDE + "/licencia-obra.json?rows=" + ROWS + "&srsname=wgs84&fl=id,geometry", raiz, List.of()),
				new Fuente("registro-licencia (locales)",
						SEDE + "/registro-licencia.json?rows=" + ROWS + "&srsname=wgs84&fl=id,geometry,codPortal",
						raiz, List.of()),
				new Fuente("via-publica/incidencia",
						SEDE + "/via-publica/incidencia.json?rows=" + ROWS + "&srsname=wgs84&fl=id,geometry", raiz,
						List.of()),
				new Fuente("via-publica/incidencia/conservacion",
						SEDE + "/via-publica/incidencia/conservacion.json?rows=" + ROWS
								+ "&srsname=wgs84&fl=id,geometry",
						raiz, List.of()),
				new Fuente("edificio-historico",
						SEDE + "/edificio-historico.json?rows=" + ROWS + "&srsname=wgs84",
						List.of("geometry", "edificio.geometry"), List.of("edificio.junta")),
				new Fuente("asociacion (censo de asociaciones)",
						SEDE + "/asociacion/list.json?rows=" + ROWS + "&srsname=wgs84", raiz, List.of("junta.id")));
		List<List<String>> rows = new ArrayList<>();
		for (Fuente f : fuentes) {
			Response r = api.tryGet(f.url());
			if (r.status() != 200 || !r.isJson()) {
				rows.add(List.of(f.nombre(), String.valueOf(r.status()), "—", "—", "—", "—", "—",
						r.body().substring(0, Math.min(60, r.body().length()))));
				continue;
			}
			JsonNode json = r.json();
			JsonNode result = json.isArray() ? json : json.path("result");
			int n = 0, conPunto = 0, conJuntaPropia = 0, resueltos = 0, fuera = 0, comparables = 0, acuerdan = 0;
			Set<String> tiposGeometria = new TreeSet<>();
			for (JsonNode item : result) {
				n++;
				JsonNode geometry = geometriaDe(item, f.geometrias());
				String tipo = text(geometry, "type");
				if (!tipo.isBlank()) {
					tiposGeometria.add(tipo);
				}
				Integer propia = juntaDeclarada(item, f.juntas());
				if (propia != null) {
					conJuntaPropia++;
				}
				double[] punto = representante(geometry);
				if (punto == null) {
					continue;
				}
				conPunto++;
				List<Integer> hits = locate(punto[0], punto[1]);
				if (hits.isEmpty()) {
					fuera++;
					continue;
				}
				resueltos++;
				if (propia != null) {
					comparables++;
					if (hits.contains(propia)) {
						acuerdan++;
					}
				}
			}
			String total = json.isArray() ? String.valueOf(n) : text(json, "totalCount");
			rows.add(List.of(f.nombre(), total, String.valueOf(n),
					porcentaje(conPunto, n), f.juntas().isEmpty() ? "—" : porcentaje(conJuntaPropia, n),
					porcentaje(resueltos, n),
					comparables == 0 ? "—" : acuerdan + "/" + comparables + " (" + soloPorcentaje(acuerdan, comparables) + ")",
					fuera + " fuera; geom " + (tiposGeometria.isEmpty() ? "(ninguna)" : tiposGeometria)));
			pause();
		}
		table(ID, List.of("fuente", "totalCount", "muestra", "con punto", "con junta propia",
				"asignables por geometría", "acuerdo con la junta propia", "notas"), rows);
	}

	/**
	 * S0.3 recomendación 5: el campo {@code district} de las quejas es texto, no clave, y llega con las tildes
	 * mal codificadas. Aquí se mide cuánto vale como contraste de la resolución geométrica.
	 */
	@Test
	@Order(9)
	void districtDeLasQuejasFrenteALaGeometria() {
		heading(ID, "Quejas: `district` (texto) frente a la junta resuelta por geometría");
		// `fl` explícito y sin `title` ni `description`: el texto libre lleva datos personales (regla 22).
		Response r = api.get(SEDE + "/quejas-sugerencias/list.json?rows=" + ROWS + "&srsname=wgs84&sort="
				+ enc("requested_datetime desc")
				+ "&fl=service_request_id,geometry,district,address_string,requested_datetime");
		metric(ID, r.summary());
		SpikeFixtures.save(FIXTURES, "quejas-district-geometria.json", r.body());
		JsonNode result = r.json().isArray() ? r.json() : r.json().path("result");
		Map<String, Integer> sinCasar = new TreeMap<>();
		int n = 0, conTexto = 0, textoCasado = 0, conPunto = 0, comparables = 0, acuerdan = 0;
		List<List<String>> desacuerdos = new ArrayList<>();
		for (JsonNode queja : result) {
			n++;
			String district = text(queja, "district");
			Integer porTexto = district.isBlank() ? null : juntaPorNombre.get(clave(district));
			if (!district.isBlank()) {
				conTexto++;
				if (porTexto != null) {
					textoCasado++;
				}
				else {
					sinCasar.merge(district, 1, Integer::sum);
				}
			}
			double[] punto = representante(queja.path("geometry"));
			if (punto == null) {
				continue;
			}
			conPunto++;
			List<Integer> hits = locate(punto[0], punto[1]);
			if (porTexto == null || hits.isEmpty()) {
				continue;
			}
			comparables++;
			if (hits.contains(porTexto)) {
				acuerdan++;
			}
			else if (desacuerdos.size() < 10) {
				desacuerdos.add(List.of(text(queja, "service_request_id"), district, nombre(hits.get(0)),
						coord(punto[0]) + ", " + coord(punto[1])));
			}
		}
		metric(ID, "quejas=" + n + " con `district`=" + porcentaje(conTexto, n) + " de ellos reconocibles=" + textoCasado
				+ " con punto=" + porcentaje(conPunto, n));
		metric(ID, "comparables (texto reconocible + punto)=" + comparables + " coinciden=" + porcentaje(acuerdan, comparables));
		if (!sinCasar.isEmpty()) {
			counts(ID, "`district` que no casa con ninguna de las 29 juntas", sinCasar);
		}
		if (!desacuerdos.isEmpty()) {
			table(ID, List.of("queja", "`district`", "junta por geometría", "punto (lon, lat)"), desacuerdos);
		}
	}

	// --- 6. cobertura del término ------------------------------------------------------------------------

	/** ¿Cubren los 29 polígonos el término municipal sin huecos ni solapes? Rejilla sobre el bbox. */
	@Test
	@Order(10)
	void coberturaYSolapeDeLosPoligonos() {
		heading(ID, "Cobertura de los 29 polígonos");
		double minLon = juntas.values().stream().mapToDouble(Junta::minLon).min().orElseThrow();
		double maxLon = juntas.values().stream().mapToDouble(Junta::maxLon).max().orElseThrow();
		double minLat = juntas.values().stream().mapToDouble(Junta::minLat).min().orElseThrow();
		double maxLat = juntas.values().stream().mapToDouble(Junta::maxLat).max().orElseThrow();
		metric(ID, String.format(Locale.ROOT, "bbox del término: lon [%.6f, %.6f] lat [%.6f, %.6f]", minLon, maxLon,
				minLat, maxLat));
		int side = 300;
		int dentro = 0, solapados = 0, fuera = 0;
		Map<String, Integer> solapes = new TreeMap<>();
		for (int i = 0; i < side; i++) {
			double lon = minLon + (maxLon - minLon) * (i + 0.5) / side;
			for (int k = 0; k < side; k++) {
				double lat = minLat + (maxLat - minLat) * (k + 0.5) / side;
				List<Integer> hits = locate(lon, lat);
				if (hits.isEmpty()) {
					fuera++;
				}
				else if (hits.size() == 1) {
					dentro++;
				}
				else {
					solapados++;
					solapes.merge(hits.stream().map(S21TerritoryResolutionSpike::nombre).toList().toString(), 1,
							Integer::sum);
				}
			}
		}
		int puntos = side * side;
		int cubiertos = dentro + solapados;
		metric(ID, String.format(Locale.ROOT,
				"rejilla %dx%d sobre el bbox (%d puntos): dentro del término %d, de ellos en más de una junta %d "
						+ "(%.2f %% de lo cubierto); fuera del término %d (%.1f %% del rectángulo, que no es el término)",
				side, side, puntos, cubiertos, solapados, cubiertos == 0 ? 0 : 100.0 * solapados / cubiertos, fuera,
				100.0 * fuera / puntos));
		if (!solapes.isEmpty()) {
			counts(ID, "juntas que se solapan", solapes);
		}
		List<List<String>> rows = juntas.values().stream()
				.map(j -> List.of(String.valueOf(j.id()), j.title(), String.valueOf(j.parts().size()),
						String.valueOf(j.parts().stream().mapToInt(p -> p.holes().size()).sum()),
						String.valueOf(j.vertices()),
						String.format(Locale.ROOT, "%.4f × %.4f", j.maxLon() - j.minLon(), j.maxLat() - j.minLat())))
				.toList();
		table(ID, List.of("id", "title", "partes", "agujeros", "vértices", "bbox (Δlon × Δlat)"), rows);
	}

	// --- utilidades -------------------------------------------------------------------------------------

	static JsonNode nodo(JsonNode item, String path) {
		JsonNode node = item;
		for (String segment : path.split("\\.")) {
			node = node.path(segment);
		}
		return node;
	}

	/** Primera geometría no vacía de los caminos conocidos de la fuente. */
	static JsonNode geometriaDe(JsonNode item, List<String> paths) {
		for (String path : paths) {
			JsonNode candidate = nodo(item, path);
			if (!text(candidate, "type").isBlank()) {
				return candidate;
			}
		}
		return item.path("geometry");
	}

	/** Junta que declara el propio registro, venga como id o como título. Nulo si no la trae. */
	static Integer juntaDeclarada(JsonNode item, List<String> paths) {
		for (String path : paths) {
			JsonNode node = nodo(item, path);
			if (node.isMissingNode() || node.isNull()) {
				continue;
			}
			if (node.isNumber()) {
				int id = node.asInt(-1);
				if (juntas.containsKey(id)) {
					return id;
				}
				continue;
			}
			String value = text(node);
			if (!value.isBlank()) {
				Integer byName = juntaPorNombre.get(clave(value));
				if (byName != null) {
					return byName;
				}
			}
		}
		return null;
	}

	/**
	 * Clave de comparación de nombres de junta: mayúsculas sin tildes ni signos, sin el prefijo
	 * «Junta Municipal/Vecinal» y sin artículo inicial. Absorbe la tilde rota que publica el callejero
	 * ({@code CASCO HISTÓRICO}) porque descarta todo lo que no sea una letra ASCII.
	 */
	static String clave(String titulo) {
		String s = java.text.Normalizer.normalize(titulo.toUpperCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
				.replaceAll("[^A-Z]", "");
		for (String prefijo : List.of("JUNTAMUNICIPAL", "JUNTAVECINAL", "JUNTA")) {
			if (s.startsWith(prefijo)) {
				s = s.substring(prefijo.length());
				break;
			}
		}
		for (String articulo : List.of("LAS", "LOS", "LA", "EL")) {
			if (s.startsWith(articulo)) {
				return s.substring(articulo.length());
			}
		}
		return s;
	}

	/** ¿Trae el texto algún carácter de control o no imprimible? (la tilde rota del callejero). */
	static boolean escapado(String value) {
		return value.chars().anyMatch(c -> c < 0x20 || (c >= 0x7f && c <= 0x9f));
	}

	static String soloPorcentaje(int parte, int total) {
		return total == 0 ? "—" : String.format(Locale.ROOT, "%.1f %%", 100.0 * parte / total);
	}

	static String porcentaje(int parte, int total) {
		return total == 0 ? "—" : String.format(Locale.ROOT, "%d (%.1f %%)", parte, 100.0 * parte / total);
	}

	/** Punto representante de una geometría: el propio punto, o la media de los vértices del anillo exterior. */
	static double[] representante(JsonNode geometry) {
		String type = text(geometry, "type");
		JsonNode c = geometry.path("coordinates");
		if (c.isMissingNode() || c.size() == 0) {
			return null;
		}
		return switch (type) {
			case "Point" -> new double[] { c.get(0).asDouble(), c.get(1).asDouble() };
			case "LineString", "MultiPoint" -> media(c);
			case "Polygon" -> media(c.get(0));
			case "MultiLineString" -> media(c.get(0));
			case "MultiPolygon" -> media(c.get(0).get(0));
			default -> null;
		};
	}

	static double[] media(JsonNode coordinates) {
		double lon = 0, lat = 0;
		int n = coordinates.size();
		if (n == 0) {
			return null;
		}
		for (JsonNode p : coordinates) {
			lon += p.get(0).asDouble();
			lat += p.get(1).asDouble();
		}
		return new double[] { lon / n, lat / n };
	}

	static String nombre(int juntaId) {
		Junta j = juntas.get(juntaId);
		return j == null ? "id " + juntaId : j.title().replace("Junta Municipal ", "").replace("Junta Vecinal ", "");
	}

	static String coord(double value) {
		return String.format(Locale.ROOT, "%.6f", value);
	}

	/** Cortesía con la API municipal entre peticiones de las muestras. */
	static void pause() {
		try {
			Thread.sleep(200);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
