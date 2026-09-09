package es.zaragoza.observatory.spikes.support;

import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Las 29 juntas con su polígono en WGS84 y un {@code contains} de andar por casa, para medir en un spike qué
 * parte de una fuente cae dentro de cada junta (ADR-011).
 * <p>
 * Es el mismo algoritmo que S2.1 llevaba dentro (ray casting sobre los anillos de {@code distrito.json}),
 * sacado aquí para no copiarlo en cada spike territorial. <b>No es código de producción</b>: en producción la
 * resolución es {@code ST_Contains} en PostGIS ({@code geo.PostgisDistrictLocator}). Sirve para medir el
 * acuerdo, no para decidir nada.
 */
public final class SpikeDistricts {

	/** Un anillo de coordenadas (lon, lat) en WGS84. */
	public record Ring(double[] lons, double[] lats) {

		public boolean contains(double lon, double lat) {
			boolean inside = false;
			int n = lons.length;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				double yi = lats[i], yj = lats[j];
				if ((yi > lat) != (yj > lat) && lon < (lons[j] - lons[i]) * (lat - yi) / (yj - yi) + lons[i]) {
					inside = !inside;
				}
			}
			return inside;
		}
	}

	/** Un polígono: anillo exterior y sus agujeros. */
	public record Part(Ring outer, List<Ring> holes) {

		public boolean contains(double lon, double lat) {
			return outer.contains(lon, lat) && holes.stream().noneMatch(hole -> hole.contains(lon, lat));
		}
	}

	public record Junta(int id, String title, List<Part> parts, double minLon, double minLat, double maxLon,
			double maxLat, int vertices) {

		public boolean contains(double lon, double lat) {
			if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) {
				return false;
			}
			return parts.stream().anyMatch(part -> part.contains(lon, lat));
		}
	}

	private final Map<Integer, Junta> juntas = new LinkedHashMap<>();

	private SpikeDistricts() {
	}

	/** Descarga {@code distrito.json?srsname=wgs84} y construye los 29 polígonos. */
	public static SpikeDistricts load(ZaragozaSpikeClient api) {
		var districts = new SpikeDistricts();
		ZaragozaSpikeClient.Response r = api.get(SEDE + "/distrito.json?srsname=wgs84&rows=100");
		if (r.status() != 200) {
			throw new IllegalStateException("distrito.json respondió " + r.status());
		}
		for (JsonNode node : r.json().path("result")) {
			Junta j = junta(node);
			districts.juntas.put(j.id(), j);
		}
		return districts;
	}

	public Map<Integer, Junta> juntas() {
		return Map.copyOf(juntas);
	}

	public int size() {
		return juntas.size();
	}

	public String title(int id) {
		Junta j = juntas.get(id);
		return j == null ? "?" : j.title();
	}

	/** Juntas que contienen el punto. Más de una revela solape entre polígonos (ADR-011: Juslibol). */
	public List<Integer> locate(double lon, double lat) {
		List<Integer> hits = new ArrayList<>();
		for (Junta j : juntas.values()) {
			if (j.contains(lon, lat)) {
				hits.add(j.id());
			}
		}
		return hits;
	}

	private static Ring ring(JsonNode coordinates) {
		int n = coordinates.size();
		double[] lons = new double[n];
		double[] lats = new double[n];
		for (int i = 0; i < n; i++) {
			lons[i] = coordinates.get(i).get(0).asDouble();
			lats[i] = coordinates.get(i).get(1).asDouble();
		}
		return new Ring(lons, lats);
	}

	private static Part part(JsonNode polygon) {
		Ring outer = ring(polygon.get(0));
		List<Ring> holes = new ArrayList<>();
		for (int i = 1; i < polygon.size(); i++) {
			holes.add(ring(polygon.get(i)));
		}
		return new Part(outer, holes);
	}

	private static Junta junta(JsonNode node) {
		JsonNode geometry = node.path("geometry");
		JsonNode coordinates = geometry.path("coordinates");
		List<Part> parts = new ArrayList<>();
		if ("Polygon".equals(text(geometry, "type"))) {
			parts.add(part(coordinates));
		}
		else {
			for (JsonNode polygon : coordinates) {
				parts.add(part(polygon));
			}
		}
		double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
		double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
		int vertices = 0;
		for (Part p : parts) {
			List<Ring> rings = new ArrayList<>();
			rings.add(p.outer());
			rings.addAll(p.holes());
			for (Ring r : rings) {
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

}
