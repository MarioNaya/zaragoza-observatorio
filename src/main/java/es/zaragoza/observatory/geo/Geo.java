package es.zaragoza.observatory.geo;

import java.util.List;

/**
 * Superficie pública del módulo {@code geo} para el resto de módulos (SPEC.md §4.3: shared kernel). Es la única
 * vía por la que una fuente territorial obtiene su junta.
 * <p>
 * Deliberadamente pequeña: resolver puntos y casar nombres. Ni las geometrías ni el padrón salen por aquí —los
 * publica la API de {@code geo}—, y ningún módulo puede llegar a {@code geo_district} ni a los puertos internos
 * (regla 3).
 */
public interface Geo {

	/** Junta que contiene el punto, con los tres estados de ADR-011 ({@code RESOLVED}/{@code AMBIGUOUS}/{@code OUTSIDE}). */
	DistrictLocation locate(GeoPoint point);

	/**
	 * Resuelve un lote de puntos en el mismo orden de entrada, con una sola consulta espacial. Las fuentes de la
	 * fase 2 llegan por páginas de cientos de registros: una consulta por página, no una por registro.
	 */
	List<DistrictLocation> locateAll(List<GeoPoint> points);

	/**
	 * Instantánea del índice de nombres (ADR-011 §5) para casar el nombre de junta que declara una fuente. Se
	 * pide una vez por lote y se usa en memoria: son 29 juntas más los sinónimos.
	 */
	DistrictNames districtNames();

	/**
	 * Las juntas con su nombre y su denominador poblacional, ordenadas por id. Es lo que necesita quien agrega
	 * por territorio para publicar el denominador y el año que usa (regla 7, ADR-011 §7).
	 */
	List<DistrictSummary> districts();

}
