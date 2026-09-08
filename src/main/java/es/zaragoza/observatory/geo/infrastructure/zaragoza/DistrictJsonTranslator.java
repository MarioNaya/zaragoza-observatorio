package es.zaragoza.observatory.geo.infrastructure.zaragoza;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.geo.domain.Boundary.Polygon;
import es.zaragoza.observatory.geo.domain.Boundary.Ring;
import es.zaragoza.observatory.geo.domain.Boundary;
import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anti-corruption layer de la capa base territorial (SPEC.md §4.4): traduce
 * {@code sede/servicio/distrito.json?srsname=wgs84} a {@link District} con su {@link Boundary} (S0.4, S2.1;
 * fixture {@code geo/distrito.json_srsname-wgs84_rows-100}).
 * <p>
 * La respuesta es el envoltorio de la sede ({@code {"totalCount":29,"result":[…]}}) y cada junta trae
 * {@code id}, {@code title} y {@code geometry} en GeoJSON. <b>El listado no trae {@code idpadron}</b> aunque el
 * Swagger declare un parámetro {@code indicadores}: comprobado el 2026-09-08, ese campo solo sale en el detalle.
 * <p>
 * Una junta sin geometría o con un tipo de geometría desconocido hace fallar la ingesta a propósito: es la capa
 * base del eje territorial y un cambio en origen tiene que verse (regla 1), no degradar en silencio.
 */
@Component
public class DistrictJsonTranslator {

	private final JsonMapper json;

	public DistrictJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	public List<District> translate(String body, Instant seenAt) {
		JsonNode result = json.readTree(body).path("result");
		if (!result.isArray()) {
			throw new IllegalArgumentException("district listing has no 'result' array");
		}
		List<District> districts = new ArrayList<>(result.size());
		for (JsonNode node : result) {
			districts.add(district(node, seenAt));
		}
		return districts;
	}

	private District district(JsonNode node, Instant seenAt) {
		int id = node.path("id").asInt(-1);
		if (id <= 0) {
			throw new IllegalArgumentException("district without id: " + node);
		}
		String title = text(node, "title");
		return new District(id, null, title, DistrictKind.fromTitle(title), boundary(id, node.path("geometry")),
				seenAt, seenAt);
	}

	/** GeoJSON {@code Polygon} o {@code MultiPolygon} en WGS84 (lon, lat). Hoy las 29 son {@code Polygon}. */
	static Boundary boundary(int districtId, JsonNode geometry) {
		String type = text(geometry, "type");
		JsonNode coordinates = geometry.path("coordinates");
		if (type == null || coordinates.isMissingNode()) {
			throw new IllegalArgumentException("district " + districtId + " has no geometry");
		}
		return switch (type) {
			case "Polygon" -> Boundary.of(polygon(districtId, coordinates));
			case "MultiPolygon" -> {
				List<Polygon> parts = new ArrayList<>(coordinates.size());
				for (JsonNode part : coordinates) {
					parts.add(polygon(districtId, part));
				}
				yield new Boundary(parts);
			}
			default -> throw new IllegalArgumentException(
					"district " + districtId + " has an unsupported geometry type: " + type);
		};
	}

	private static Polygon polygon(int districtId, JsonNode rings) {
		if (!rings.isArray() || rings.isEmpty()) {
			throw new IllegalArgumentException("district " + districtId + " has a polygon without rings");
		}
		Ring exterior = ring(districtId, rings.get(0));
		List<Ring> holes = new ArrayList<>(Math.max(0, rings.size() - 1));
		for (int i = 1; i < rings.size(); i++) {
			holes.add(ring(districtId, rings.get(i)));
		}
		return new Polygon(exterior, holes);
	}

	private static Ring ring(int districtId, JsonNode coordinates) {
		if (!coordinates.isArray()) {
			throw new IllegalArgumentException("district " + districtId + " has a ring that is not an array");
		}
		List<GeoPoint> points = new ArrayList<>(coordinates.size());
		for (JsonNode position : coordinates) {
			if (!position.isArray() || position.size() < 2) {
				throw new IllegalArgumentException("district " + districtId + " has a malformed position: " + position);
			}
			points.add(new GeoPoint(position.get(0).asDouble(), position.get(1).asDouble()));
		}
		return new Ring(points);
	}

	static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String s = value.isString() ? value.stringValue() : value.toString();
		return s.isBlank() ? null : s.strip();
	}

}
