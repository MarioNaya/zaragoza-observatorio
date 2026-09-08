package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.geo.DistrictLocation;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.geo.domain.DistrictLocator;
import jakarta.persistence.EntityManager;

/**
 * Resolución punto → junta con PostGIS (ADR-011): {@code ST_Contains} sobre {@code geo_district.boundary}, con el
 * índice GiST de V008. Es la única vía de asignación territorial del proyecto; la API municipal no la ofrece
 * (S2.1).
 * <p>
 * Se devuelven <b>todas</b> las juntas que contienen el punto, ordenadas por id, y es
 * {@link DistrictLocation#of(List)} quien decide: una es {@code RESOLVED}, varias son {@code AMBIGUOUS} y
 * ninguna es {@code OUTSIDE}. Los polígonos publicados se solapan en el entorno de Juslibol (S2.1), así que el
 * caso de varias no es teórico.
 * <p>
 * Un punto exactamente sobre el borde de un polígono queda fuera: {@code ST_Contains} excluye la frontera. Es un
 * criterio, y se declara en los {@code caveats} en vez de elegir {@code ST_Intersects} y que un punto de borde
 * cuente en dos juntas.
 */
@Repository
class PostgisDistrictLocator implements DistrictLocator {

	/** Tope de puntos por consulta: por encima se trocea, para no generar un SQL desmedido. */
	static final int BATCH = 500;

	private final EntityManager entityManager;

	PostgisDistrictLocator(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	@Transactional(readOnly = true)
	public DistrictLocation locate(GeoPoint point) {
		return locateAll(List.of(point)).get(0);
	}

	@Override
	@Transactional(readOnly = true)
	public List<DistrictLocation> locateAll(List<GeoPoint> points) {
		if (points == null || points.isEmpty()) {
			return List.of();
		}
		List<List<Integer>> containing = new ArrayList<>(points.size());
		for (int i = 0; i < points.size(); i++) {
			containing.add(new ArrayList<>(1));
		}
		for (int from = 0; from < points.size(); from += BATCH) {
			int to = Math.min(points.size(), from + BATCH);
			query(points.subList(from, to), from, containing);
		}
		return containing.stream().map(DistrictLocation::of).toList();
	}

	private void query(List<GeoPoint> batch, int offset, List<List<Integer>> containing) {
		var sql = new StringBuilder("WITH pts (idx, geom) AS (VALUES ");
		for (int i = 0; i < batch.size(); i++) {
			GeoPoint point = batch.get(i);
			if (i > 0) {
				sql.append(',');
			}
			// Los valores son primitivos formateados aquí, no texto de entrada: no hay concatenación de datos
			// ajenos. Se hace así porque un array de dos columnas como parámetro no es portable entre drivers.
			sql.append("(CAST(").append(offset + i).append(" AS integer), ST_SetSRID(ST_MakePoint(")
					.append(number(point.lon())).append(',').append(number(point.lat())).append("), 4326))");
		}
		sql.append(") SELECT pts.idx, d.id FROM pts JOIN geo_district d ON ST_Contains(d.boundary, pts.geom) ")
				.append("ORDER BY pts.idx, d.id");
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createNativeQuery(sql.toString()).getResultList();
		for (Object[] row : rows) {
			int index = ((Number) row[0]).intValue();
			containing.get(index).add(((Number) row[1]).intValue());
		}
	}

	private static String number(double value) {
		return String.format(Locale.ROOT, "%.10f", value);
	}

}
