package es.zaragoza.observatory.urban.domain;

import java.time.Instant;
import java.util.List;

import es.zaragoza.observatory.geo.GeoPoint;

/**
 * Un local recién traducido del JSON, <b>antes</b> de resolver su territorio. Existe por lo mismo que el de
 * {@code citizen}: la resolución es un {@code JOIN} espacial por lote (ADR-011), así que primero se traduce la
 * página entera y después se resuelven de una vez sus puntos.
 */
public record LicensedPremisesDraft(int sourceId, IaeActivity activity, int statusCode, String portalCode,
		String streetCode, String saturatedZone, Instant createdAt, Instant updatedAt, Instant deregisteredAt,
		GeoPoint point, List<Licence> licences) {

	public LicensedPremisesDraft {
		licences = licences == null ? List.of() : List.copyOf(licences);
	}

}
