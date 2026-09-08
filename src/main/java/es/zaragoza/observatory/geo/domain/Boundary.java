package es.zaragoza.observatory.geo.domain;

import java.util.List;
import java.util.Objects;

/**
 * El contorno de una junta en WGS84, tal como lo devuelve {@code distrito.json?srsname=wgs84}: uno o varios
 * polígonos, cada uno con su anillo exterior y sus posibles agujeros (S0.4, S2.1).
 * <p>
 * El dominio no sabe de GeoJSON ni de PostGIS: guarda las coordenadas y deja la serialización y la consulta
 * espacial al adaptador (regla 4). Hoy las 29 juntas son un único polígono sin agujeros (S2.1), pero el modelo
 * admite las dos formas porque es lo que dice el formato de origen, no lo que hoy se recibe.
 */
public record Boundary(List<Polygon> polygons) {

	public Boundary {
		Objects.requireNonNull(polygons, "polygons must not be null");
		if (polygons.isEmpty()) {
			throw new IllegalArgumentException("a boundary needs at least one polygon");
		}
		polygons = List.copyOf(polygons);
	}

	public static Boundary of(Polygon polygon) {
		return new Boundary(List.of(polygon));
	}

	/** Vértices totales; útil para trazas y para detectar una geometría que se degrada en origen. */
	public int vertices() {
		return polygons.stream().mapToInt(Polygon::vertices).sum();
	}

	/** Un polígono: el anillo exterior en primer lugar y después los agujeros, como en GeoJSON. */
	public record Polygon(Ring exterior, List<Ring> holes) {

		public Polygon {
			Objects.requireNonNull(exterior, "exterior ring must not be null");
			holes = holes == null ? List.of() : List.copyOf(holes);
		}

		public static Polygon of(Ring exterior) {
			return new Polygon(exterior, List.of());
		}

		/** Los anillos en el orden de GeoJSON: exterior primero. */
		public List<Ring> rings() {
			if (holes.isEmpty()) {
				return List.of(exterior);
			}
			var all = new java.util.ArrayList<Ring>(holes.size() + 1);
			all.add(exterior);
			all.addAll(holes);
			return List.copyOf(all);
		}

		public int vertices() {
			return rings().stream().mapToInt(ring -> ring.points().size()).sum();
		}
	}

	/**
	 * Un anillo cerrado. GeoJSON exige que el primer punto y el último coincidan y al menos cuatro posiciones;
	 * se comprueba aquí para que una geometría rota se vea en la ingesta y no al consultar (regla 1).
	 */
	public record Ring(List<GeoPoint> points) {

		public Ring {
			Objects.requireNonNull(points, "points must not be null");
			points = List.copyOf(points);
			if (points.size() < 4) {
				throw new IllegalArgumentException("a linear ring needs at least 4 positions, got " + points.size());
			}
			if (!points.get(0).equals(points.get(points.size() - 1))) {
				throw new IllegalArgumentException("a linear ring must be closed: first and last position differ");
			}
		}
	}

}
