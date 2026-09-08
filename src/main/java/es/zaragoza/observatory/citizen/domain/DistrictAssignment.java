package es.zaragoza.observatory.citizen.domain;

import es.zaragoza.observatory.geo.DistrictLocation;

/**
 * La junta de un registro, con los dos campos que ADR-011 §3 exige y que nunca se funden: la <b>resuelta</b> por
 * geometría y la <b>declarada</b> por el origen.
 * <p>
 * El declarado no sustituye al resuelto ni siquiera cuando el resuelto falta: si una queja no trae punto pero
 * dice «DELICIAS», el registro sigue siendo {@code NO_POINT} y su {@code districtId} sigue vacío. Lo declarado
 * se guarda para poder medir la discrepancia, que es un dato sobre la calidad de la fuente, no un relleno.
 *
 * @param status resultado de la resolución geométrica
 * @param districtId junta resuelta, {@code null} salvo en {@code RESOLVED}/{@code AMBIGUOUS}
 * @param declaredName nombre de junta que publica el origen, tal cual (con su codificación rota si la trae)
 * @param declaredDistrictId ese nombre casado contra las juntas oficiales (ADR-011 §5), {@code null} si no casa
 */
public record DistrictAssignment(Assignment status, Integer districtId, String declaredName,
		Integer declaredDistrictId) {

	public DistrictAssignment {
		if (status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		if (status.hasDistrict() != (districtId != null)) {
			throw new IllegalArgumentException("districtId is present exactly for RESOLVED and AMBIGUOUS, got "
					+ status + " with districtId " + districtId);
		}
		declaredName = declaredName == null || declaredName.isBlank() ? null : declaredName.strip();
	}

	/** Registro sin punto: no se asigna junta aunque el origen declare una (ADR-011 §2). */
	public static DistrictAssignment withoutPoint(String declaredName, Integer declaredDistrictId) {
		return new DistrictAssignment(Assignment.NO_POINT, null, declaredName, declaredDistrictId);
	}

	/** Traduce el resultado de {@code geo} conservando lo declarado. */
	public static DistrictAssignment of(DistrictLocation location, String declaredName, Integer declaredDistrictId) {
		Assignment status = switch (location.status()) {
			case RESOLVED -> Assignment.RESOLVED;
			case AMBIGUOUS -> Assignment.AMBIGUOUS;
			case OUTSIDE -> Assignment.OUTSIDE;
		};
		return new DistrictAssignment(status, location.districtId(), declaredName, declaredDistrictId);
	}

	/** Las dos vías dan la misma junta. */
	public boolean agrees() {
		return districtId != null && districtId.equals(declaredDistrictId);
	}

	/** Las dos vías dan juntas distintas: es un hecho publicable sobre la fuente, no un error que se corrija. */
	public boolean disagrees() {
		return districtId != null && declaredDistrictId != null && !districtId.equals(declaredDistrictId);
	}

}
