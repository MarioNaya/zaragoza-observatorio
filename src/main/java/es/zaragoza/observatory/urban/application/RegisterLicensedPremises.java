package es.zaragoza.observatory.urban.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.geo.DistrictLocation;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.urban.domain.LicensedPremises;
import es.zaragoza.observatory.urban.domain.LicensedPremisesDraft;
import es.zaragoza.observatory.urban.domain.PremisesRepository;

/**
 * Registra una página de locales: resuelve el territorio de todos de una vez y persiste con upsert idempotente
 * (regla 5).
 * <p>
 * El territorio se resuelve como manda ADR-011: <b>una</b> consulta espacial por página en vez de una por
 * registro, y la junta sale de {@code ST_Contains}, nunca de la API municipal. Un local sin punto se queda sin
 * junta —son 4.499 de 42.342— y no se geocodifica por dirección para rellenar el hueco.
 * <p>
 * Aquí no hay nada que casar contra lo declarado, al contrario que en {@code citizen}: esta fuente no declara
 * junta (ADR-016 §5).
 */
public class RegisterLicensedPremises {

	private final PremisesRepository premises;
	private final Geo geo;

	public RegisterLicensedPremises(PremisesRepository premises, Geo geo) {
		this.premises = Objects.requireNonNull(premises);
		this.geo = Objects.requireNonNull(geo);
	}

	@Transactional
	public int register(List<LicensedPremisesDraft> page, Instant seenAt) {
		if (page.isEmpty()) {
			return 0;
		}
		List<GeoPoint> points = new ArrayList<>();
		for (LicensedPremisesDraft draft : page) {
			if (draft.point() != null) {
				points.add(draft.point());
			}
		}
		List<DistrictLocation> locations = geo.locateAll(points);

		List<LicensedPremises> resolved = new ArrayList<>(page.size());
		int next = 0;
		for (LicensedPremisesDraft draft : page) {
			Assignment assignment = Assignment.NO_POINT;
			Integer districtId = null;
			if (draft.point() != null) {
				DistrictLocation location = locations.get(next++);
				assignment = Assignment.of(location);
				districtId = location.districtId();
			}
			resolved.add(new LicensedPremises(draft.sourceId(), draft.activity(), draft.statusCode(),
					draft.portalCode(), draft.streetCode(), draft.saturatedZone(), draft.createdAt(),
					draft.updatedAt(), draft.deregisteredAt(), draft.point(), districtId, assignment,
					draft.licences(), seenAt, seenAt));
		}
		return premises.upsertAll(resolved, seenAt);
	}

}
