package es.zaragoza.observatory.citizen.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.citizen.domain.DistrictAssignment;
import es.zaragoza.observatory.citizen.domain.ServiceRequest;
import es.zaragoza.observatory.citizen.domain.ServiceRequestDraft;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.geo.DistrictLocation;
import es.zaragoza.observatory.geo.DistrictNames;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.geo.GeoPoint;

/**
 * Registra una página de quejas: resuelve el territorio de todas de una vez y persiste con upsert idempotente
 * (regla 5).
 * <p>
 * El territorio se resuelve como manda ADR-011: <b>una</b> consulta espacial por página en vez de una por
 * registro, la junta sale de {@code ST_Contains} y nunca de la API municipal, y el nombre que declara el origen
 * se guarda aparte —casado contra las juntas oficiales cuando se puede— sin sustituir jamás al resuelto. Un
 * registro sin punto se queda sin junta aunque declare una: rellenarlo sería inventarse el dato.
 */
public class RegisterServiceRequests {

	private final ServiceRequestRepository requests;
	private final Geo geo;

	public RegisterServiceRequests(ServiceRequestRepository requests, Geo geo) {
		this.requests = Objects.requireNonNull(requests);
		this.geo = Objects.requireNonNull(geo);
	}

	@Transactional
	public int register(List<ServiceRequestDraft> page, Instant seenAt) {
		if (page.isEmpty()) {
			return 0;
		}
		DistrictNames names = geo.districtNames();

		List<GeoPoint> points = new ArrayList<>();
		for (ServiceRequestDraft draft : page) {
			if (draft.point() != null) {
				points.add(draft.point());
			}
		}
		List<DistrictLocation> locations = geo.locateAll(points);

		List<ServiceRequest> resolved = new ArrayList<>(page.size());
		int next = 0;
		for (ServiceRequestDraft draft : page) {
			Integer declaredId = names.resolve(draft.declaredDistrict()).orElse(null);
			DistrictAssignment assignment = draft.point() == null
					? DistrictAssignment.withoutPoint(draft.declaredDistrict(), declaredId)
					: DistrictAssignment.of(locations.get(next++), draft.declaredDistrict(), declaredId);
			resolved.add(new ServiceRequest(draft.sourceId(), draft.status(), draft.serviceCode(),
					draft.serviceName(), draft.requestedAt(), draft.updatedAt(), draft.point(), assignment, seenAt,
					seenAt));
		}
		return requests.upsertAll(resolved, seenAt);
	}

}
