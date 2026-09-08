package es.zaragoza.observatory.citizen.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import es.zaragoza.observatory.geo.DistrictLocation;
import es.zaragoza.observatory.geo.DistrictNames;
import es.zaragoza.observatory.geo.DistrictSummary;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.geo.GeoPoint;

/**
 * Doble de {@link Geo}: resuelve por una tabla de puntos conocidos. Basta para comprobar cómo se comporta
 * {@code citizen} ante cada uno de los cuatro resultados; que {@code ST_Contains} sea correcto es cosa de
 * {@code GeoIntegrationTests}.
 */
public final class FakeGeo implements Geo {

	private final Map<GeoPoint, List<Integer>> containing = new LinkedHashMap<>();
	private final Map<Integer, String> names = new LinkedHashMap<>();
	private final Map<Integer, Integer> population = new LinkedHashMap<>();

	public FakeGeo district(int id, String name, GeoPoint point, Integer inhabitants) {
		names.put(id, name);
		if (point != null) {
			containing.computeIfAbsent(point, key -> new ArrayList<>()).add(id);
		}
		if (inhabitants != null) {
			population.put(id, inhabitants);
		}
		return this;
	}

	/** Añade un punto que cae en dos juntas a la vez (el solape de Juslibol, S2.1). */
	public FakeGeo overlap(GeoPoint point, int... districtIds) {
		var ids = new ArrayList<Integer>();
		for (int id : districtIds) {
			ids.add(id);
		}
		containing.put(point, ids);
		return this;
	}

	@Override
	public DistrictLocation locate(GeoPoint point) {
		return DistrictLocation.of(containing.getOrDefault(point, List.of()));
	}

	@Override
	public List<DistrictLocation> locateAll(List<GeoPoint> points) {
		return points.stream().map(this::locate).toList();
	}

	@Override
	public DistrictNames districtNames() {
		return DistrictNames.of(names);
	}

	@Override
	public List<DistrictSummary> districts() {
		return names.entrySet().stream()
				.map(entry -> new DistrictSummary(entry.getKey(), entry.getValue(), entry.getValue(), null,
						population.get(entry.getKey()), population.containsKey(entry.getKey()) ? 2024 : null))
				.toList();
	}

}
