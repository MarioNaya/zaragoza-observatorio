package es.zaragoza.observatory.citizen.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import es.zaragoza.observatory.citizen.Citizen;
import es.zaragoza.observatory.citizen.CitizenSources;
import es.zaragoza.observatory.citizen.domain.AggregationAxis;
import es.zaragoza.observatory.citizen.domain.AggregationBucket;
import es.zaragoza.observatory.citizen.domain.AssignmentCounts;
import es.zaragoza.observatory.citizen.domain.InternalServices;
import es.zaragoza.observatory.citizen.domain.ServiceRequestQuery;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.shared.TerritorialTally;

/**
 * Implementación de la superficie pública de {@code citizen} (ADR-019 §9). Traduce la ventana del cruce a los
 * filtros del módulo y devuelve el recuento por junta <b>con</b> su cobertura: las dos cosas salen de la misma
 * consulta filtrada, así que no pueden desacompasarse.
 */
public class CitizenService implements Citizen {

	private final ServiceRequestRepository requests;
	private final Ingestion ingestion;

	public CitizenService(ServiceRequestRepository requests, Ingestion ingestion) {
		this.requests = Objects.requireNonNull(requests);
		this.ingestion = Objects.requireNonNull(ingestion);
	}

	@Override
	public TerritorialTally requestsByDistrict(DateWindow window) {
		DateWindow bounds = window == null ? DateWindow.open() : window;
		// INCLUDE: los servicios INTERNAL cuentan, como en el resto de la API del módulo (ADR-015).
		ServiceRequestQuery filters = new ServiceRequestQuery(null, null, null, null, InternalServices.Filter.INCLUDE,
				bounds.from(), bounds.to(), ServiceRequestQuery.SortField.REQUESTED_AT, false, 0,
				ServiceRequestQuery.MAX_SIZE).filtersOnly();

		Map<Integer, Long> byDistrict = new LinkedHashMap<>();
		for (AggregationBucket bucket : requests.aggregate(AggregationAxis.DISTRICT, filters)) {
			Integer districtId = districtId(bucket.key());
			if (districtId != null) {
				byDistrict.merge(districtId, bucket.total(), Long::sum);
			}
		}

		AssignmentCounts counts = requests.assignmentCounts(filters);
		long total = counts.total();
		long withoutPoint = counts.byAssignment().getOrDefault(Assignment.NO_POINT, 0L);
		long assigned = counts.byAssignment().getOrDefault(Assignment.RESOLVED, 0L)
				+ counts.byAssignment().getOrDefault(Assignment.AMBIGUOUS, 0L);
		return new TerritorialTally(byDistrict, total, total - withoutPoint, assigned);
	}

	@Override
	public Instant ingestedAt() {
		Instant altas = lastRun(CitizenSources.REQUESTS);
		Instant cierres = lastRun(CitizenSources.CLOSURES);
		if (altas == null) {
			return cierres;
		}
		if (cierres == null) {
			return altas;
		}
		return altas.isAfter(cierres) ? altas : cierres;
	}

	private Instant lastRun(DatasetRef dataset) {
		return ingestion.lastSuccessful(dataset).map(IngestionRunSummary::finishedAt).orElse(null);
	}

	/** La clave del eje territorial es el id de junta; cualquier otra cosa no es una junta y no se cuenta. */
	private static Integer districtId(String key) {
		try {
			return Integer.valueOf(key);
		}
		catch (NumberFormatException | NullPointerException ex) {
			return null;
		}
	}

}
