package es.zaragoza.observatory.urban.domain;

import java.time.Instant;
import java.util.List;

import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.geo.GeoPoint;

/**
 * Un local con licencia del registro municipal (S2.4, ADR-016). Lo que no lleva es deliberado:
 * <ul>
 * <li><b>sin texto libre de ninguna clase</b>: ni {@code comments} (15 DNI con letra válida), ni
 * {@code emplazamiento} (dirección, sin uso desde ADR-011 §2), ni {@code actividad} (redundante con el epígrafe
 * IAE y con dos DNI dentro). ADR-016 §3;</li>
 * <li><b>sin junta declarada</b>: esta fuente no declara ninguna, y una columna siempre nula no es un contraste
 * (ADR-016 §5).</li>
 * </ul>
 * Un local <b>no es un negocio abierto</b>. El registro guarda licencias concedidas; {@code statusCode} no tiene
 * taxonomía publicada y {@code deregisteredAt} solo aparece en 169 de 42.342. Ninguna lectura de este agregado
 * puede llamarse «locales activos» (regla 6).
 *
 * @param sourceId {@code id} del origen
 * @param activity epígrafe IAE, nunca {@code null} ({@link IaeActivity#NONE} si el origen no lo trae)
 * @param statusCode {@code estado} crudo (0..3), sin etiqueta
 * @param portalCode {@code codPortal}: agrupa locales del mismo portal
 * @param streetCode {@code codVia}
 * @param saturatedZone {@code zonaSaturada}: código de una letra; los datos usan 17 y la taxonomía publica 15
 * @param createdAt {@code creationDate} convertida desde hora local de Zaragoza (S0.5)
 * @param updatedAt {@code lastUpdated}; marca de agua del incremental
 * @param deregisteredAt {@code fechaBaja}, {@code null} en la inmensa mayoría
 * @param point punto en WGS84, {@code null} en el 10,6 % de los locales
 * @param districtId junta resuelta por geometría, {@code null} salvo {@code RESOLVED}/{@code AMBIGUOUS}
 * @param assignment cómo quedó la resolución territorial, con sus cuatro estados
 * @param licences licencias del local, ordenadas por año y expediente
 * @param firstSeenAt primera ingesta en la que apareció
 * @param lastSeenAt última ingesta en la que apareció
 */
public record LicensedPremises(int sourceId, IaeActivity activity, int statusCode, String portalCode,
		String streetCode, String saturatedZone, Instant createdAt, Instant updatedAt, Instant deregisteredAt,
		GeoPoint point, Integer districtId, Assignment assignment, List<Licence> licences, Instant firstSeenAt,
		Instant lastSeenAt) {

	public LicensedPremises {
		if (sourceId < 0) {
			throw new IllegalArgumentException("sourceId must not be negative, got " + sourceId);
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt must not be null (premises " + sourceId + ")");
		}
		if (assignment == null) {
			throw new IllegalArgumentException("assignment must not be null (premises " + sourceId + ")");
		}
		if ((point == null) != (assignment == Assignment.NO_POINT)) {
			throw new IllegalArgumentException(
					"a premises has a point exactly when its assignment is not NO_POINT (premises " + sourceId + ")");
		}
		if (assignment.hasDistrict() != (districtId != null)) {
			throw new IllegalArgumentException("districtId is present exactly for RESOLVED and AMBIGUOUS, got "
					+ assignment + " with districtId " + districtId + " (premises " + sourceId + ")");
		}
		activity = activity == null ? IaeActivity.NONE : activity;
		licences = licences == null ? List.of() : List.copyOf(licences);
		portalCode = blankToNull(portalCode);
		streetCode = blankToNull(streetCode);
		saturatedZone = blankToNull(saturatedZone);
	}

	/** El año de la licencia más antigua, que es lo más parecido a «desde cuándo hay actividad registrada». */
	public Integer firstLicenceYear() {
		return licences.stream().mapToInt(Licence::year).min().stream().boxed().findFirst().orElse(null);
	}

	public LicensedPremises seen(Instant firstSeenAt, Instant lastSeenAt) {
		return new LicensedPremises(sourceId, activity, statusCode, portalCode, streetCode, saturatedZone, createdAt,
				updatedAt, deregisteredAt, point, districtId, assignment, licences, firstSeenAt, lastSeenAt);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
