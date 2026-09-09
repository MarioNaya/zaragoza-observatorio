package es.zaragoza.observatory.citizen.support;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import es.zaragoza.observatory.citizen.domain.AggregationAxis;
import es.zaragoza.observatory.citizen.domain.AggregationBucket;
import es.zaragoza.observatory.citizen.domain.Assignment;
import es.zaragoza.observatory.citizen.domain.AssignmentCounts;
import es.zaragoza.observatory.citizen.domain.ServiceRequest;
import es.zaragoza.observatory.citizen.domain.ServiceRequestPage;
import es.zaragoza.observatory.citizen.domain.ServiceRequestQuery;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.citizen.domain.ServiceRequestStatus;
import es.zaragoza.observatory.citizen.domain.YearCoverage;

/**
 * Doble de {@link ServiceRequestRepository} para tests unitarios. Solo implementa de verdad lo que esos tests
 * usan: el upsert idempotente y las dos marcas de agua; agregar y buscar es cosa del adaptador real y se
 * comprueba contra PostgreSQL en {@code CitizenIntegrationTests}.
 */
public final class InMemoryServiceRequests implements ServiceRequestRepository {

	public final Map<Long, ServiceRequest> byId = new LinkedHashMap<>();

	@Override
	public int upsertAll(List<ServiceRequest> requests, Instant seenAt) {
		for (ServiceRequest request : requests) {
			ServiceRequest existing = byId.get(request.sourceId());
			byId.put(request.sourceId(),
					existing == null ? request : request.seen(existing.firstSeenAt(), seenAt));
		}
		return requests.size();
	}

	@Override
	public Optional<Instant> latestRequestedAt() {
		return byId.values().stream().map(ServiceRequest::requestedAt).max(Comparator.naturalOrder());
	}

	@Override
	public Optional<Instant> latestUpdatedAt() {
		return byId.values().stream().map(ServiceRequest::updatedAt).filter(java.util.Objects::nonNull)
				.max(Comparator.naturalOrder());
	}

	@Override
	public Optional<Instant> earliestRequestedAt() {
		return byId.values().stream().map(ServiceRequest::requestedAt).min(Comparator.naturalOrder());
	}

	@Override
	public long count() {
		return byId.size();
	}

	@Override
	public ServiceRequestPage search(ServiceRequestQuery query) {
		List<ServiceRequest> items = List.copyOf(byId.values());
		return new ServiceRequestPage(items, items.size(), query.page(), query.size());
	}

	@Override
	public long count(ServiceRequestQuery filters) {
		return byId.size();
	}

	@Override
	public List<YearCoverage> pointCoverageByYear(ServiceRequestQuery filters) {
		return List.of();
	}

	@Override
	public List<AggregationBucket> aggregate(AggregationAxis axis, ServiceRequestQuery filters) {
		return List.of();
	}

	@Override
	public AssignmentCounts assignmentCounts(ServiceRequestQuery filters) {
		var counts = new EnumMap<Assignment, Long>(Assignment.class);
		for (Assignment assignment : Assignment.values()) {
			counts.put(assignment, 0L);
		}
		byId.values().forEach(request -> counts.merge(request.district().status(), 1L, Long::sum));
		return new AssignmentCounts(counts, 0, 0, 0, 0);
	}

	@Override
	public Map<ServiceRequestStatus, Long> statusCounts(ServiceRequestQuery filters) {
		var counts = new EnumMap<ServiceRequestStatus, Long>(ServiceRequestStatus.class);
		byId.values().forEach(request -> counts.merge(request.status(), 1L, Long::sum));
		return counts;
	}

}
