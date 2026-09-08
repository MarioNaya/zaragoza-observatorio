package es.zaragoza.observatory.geo.domain;

/**
 * Un punto en WGS84 (EPSG:4326), en el orden en que lo publica la API municipal con {@code srsname=wgs84} y en
 * el que lo escribe GeoJSON: longitud primero, latitud después (S0.5, S2.1).
 * <p>
 * Se valida el rango porque el error más probable al integrar una fuente nueva es invertir los dos números:
 * Zaragoza está en longitud −1,17..−0,68 y latitud 41,45..41,93, así que un punto invertido cae fuera del rango
 * de la longitud y se detecta aquí en vez de resolverse silenciosamente a «fuera del término».
 *
 * @param lon longitud en grados decimales
 * @param lat latitud en grados decimales
 */
public record GeoPoint(double lon, double lat) {

	public GeoPoint {
		if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
			throw new IllegalArgumentException("lon must be a finite value in [-180, 180], got " + lon);
		}
		if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
			throw new IllegalArgumentException("lat must be a finite value in [-90, 90], got " + lat);
		}
	}

}
