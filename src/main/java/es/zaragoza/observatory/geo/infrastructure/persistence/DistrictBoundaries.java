package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/**
 * Devuelve el contorno almacenado de cada junta como geometría GeoJSON, para que el mapa pueda pintarlas
 * (ADR-020 §4).
 * <p>
 * Es deliberadamente un adaptador y <b>no</b> un puerto del dominio. Aquí no hay ninguna operación de dominio:
 * la única del contorno es «¿contiene este punto?», y esa ya vive en {@link PostgisDistrictLocator}. Esto es
 * devolver lo que se guardó, en el formato en que se guardó, y hacerlo pasar por {@code Boundary} obligaría a
 * analizar 16.462 vértices para volver a serializarlos idénticos. {@code ST_AsGeoJSON} los emite directamente.
 * <p>
 * La geometría se publica <b>sin simplificar</b>: es el polígono oficial tal como lo entrega
 * {@code distrito.json?srsname=wgs84}, y una tolerancia elegida aquí sería el observatorio redibujando el
 * término municipal por comodidad del navegador (regla 6). Simplificar, si hace falta, es cosa de quien pinta.
 */
@Component
public class DistrictBoundaries {

	/**
	 * Seis decimales de grado son ~11 cm, de sobra para un mapa y la mitad de bytes que los ocho con que se
	 * escribió. No cambia la geometría almacenada ni la resolución de puntos, que sigue en PostGIS con la suya.
	 */
	private static final String AS_GEOJSON = """
			SELECT id, ST_AsGeoJSON(boundary, 6)
			FROM geo_district
			ORDER BY id ASC
			""";

	private final EntityManager entityManager;

	DistrictBoundaries(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/** Contorno de cada junta por id, en orden de id (regla 8). El valor es geometría GeoJSON ya serializada. */
	@Transactional(readOnly = true)
	public Map<Integer, String> findAll() {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createNativeQuery(AS_GEOJSON).getResultList();
		var boundaries = new LinkedHashMap<Integer, String>(rows.size() * 2);
		for (Object[] row : rows) {
			String geometry = (String) row[1];
			if (geometry != null) {
				boundaries.put(((Number) row[0]).intValue(), geometry);
			}
		}
		return boundaries;
	}

}
