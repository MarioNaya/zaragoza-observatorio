package es.zaragoza.observatory.geo.domain;

import java.util.List;

/**
 * Puerto de resolución territorial (ADR-011): dado un punto, la junta que lo contiene. Es la única vía admitida
 * para asignar territorio en todo el proyecto.
 * <p>
 * Se implementa con {@code ST_Contains} sobre las geometrías de {@code geo_district}, <b>no</b> preguntando a la
 * API municipal: S2.1 comprobó que el recurso {@code portalero/v2} es un buscador de direcciones que acierta la
 * junta el 89,5 % de las veces sin permitir distinguir el acierto del fallo, y que los parámetros
 * {@code point}/{@code distance} que documenta el Swagger no filtran por proximidad. La vía geométrica coincide
 * con la junta oficial en el 99,69 % de 1.308 registros contrastados.
 */
public interface DistrictLocator {

	DistrictLocation locate(GeoPoint point);

	/**
	 * Resuelve varios puntos de una vez, en el mismo orden de entrada. Existe porque las fuentes de la fase 2
	 * llegan por lotes (42.321 locales, 500 quejas por página): una consulta por lote, no una por registro.
	 */
	List<DistrictLocation> locateAll(List<GeoPoint> points);

}
