package es.zaragoza.observatory.geo;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;

/** Referencias de las fuentes que ingiere el módulo {@code geo} (S0.4, S2.1). */
public final class GeoSources {

	/**
	 * Las 29 juntas con su geometría: {@code sede/servicio/distrito.json?srsname=wgs84}. Una sola petición; el
	 * detalle de cada junta (padrón e {@code idpadron}) se lee después, junta a junta (ADR-011).
	 */
	public static final DatasetRef DISTRICTS = DatasetRef.of(Sources.SEDE, "distrito");

	private GeoSources() {
	}

}
