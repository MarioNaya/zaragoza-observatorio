package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.util.List;
import java.util.Locale;

import es.zaragoza.observatory.geo.domain.Boundary;
import es.zaragoza.observatory.geo.domain.Boundary.Polygon;
import es.zaragoza.observatory.geo.domain.Boundary.Ring;
import es.zaragoza.observatory.geo.domain.GeoPoint;

/**
 * Escribe un {@link Boundary} como GeoJSON para {@code ST_GeomFromGeoJSON}. Se genera a mano y no con Jackson
 * porque son 16.462 vértices de números: construir el árbol de objetos para volver a serializarlo sería trabajo
 * y memoria por nada.
 * <p>
 * Los números se formatean en {@link Locale#ROOT} —con punto decimal— y con {@code %.8f}: ocho decimales de
 * grado son ~1 mm, muy por debajo de la precisión del dato de origen, y evitan la notación científica, que
 * {@code ST_GeomFromGeoJSON} no admite.
 */
final class GeoJson {

	private GeoJson() {
	}

	static String of(Boundary boundary) {
		var out = new StringBuilder(boundary.vertices() * 26 + 64);
		List<Polygon> polygons = boundary.polygons();
		if (polygons.size() == 1) {
			out.append("{\"type\":\"Polygon\",\"coordinates\":");
			appendPolygon(out, polygons.get(0));
		}
		else {
			out.append("{\"type\":\"MultiPolygon\",\"coordinates\":[");
			for (int i = 0; i < polygons.size(); i++) {
				if (i > 0) {
					out.append(',');
				}
				appendPolygon(out, polygons.get(i));
			}
			out.append(']');
		}
		return out.append('}').toString();
	}

	private static void appendPolygon(StringBuilder out, Polygon polygon) {
		out.append('[');
		List<Ring> rings = polygon.rings();
		for (int i = 0; i < rings.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			appendRing(out, rings.get(i));
		}
		out.append(']');
	}

	private static void appendRing(StringBuilder out, Ring ring) {
		out.append('[');
		List<GeoPoint> points = ring.points();
		for (int i = 0; i < points.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			GeoPoint point = points.get(i);
			out.append('[').append(number(point.lon())).append(',').append(number(point.lat())).append(']');
		}
		out.append(']');
	}

	private static String number(double value) {
		String text = String.format(Locale.ROOT, "%.8f", value);
		// Quita los ceros finales para no inflar el documento; deja al menos un decimal.
		int end = text.length();
		while (end > 0 && text.charAt(end - 1) == '0') {
			end--;
		}
		if (end > 0 && text.charAt(end - 1) == '.') {
			end++;
		}
		return text.substring(0, end);
	}

}
