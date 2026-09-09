package es.zaragoza.observatory.citizen.infrastructure.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import es.zaragoza.observatory.citizen.CitizenSources;
import es.zaragoza.observatory.citizen.domain.AggregationAxis;
import es.zaragoza.observatory.citizen.domain.AggregationBucket;
import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.citizen.domain.AssignmentCounts;
import es.zaragoza.observatory.citizen.domain.InternalServices;
import es.zaragoza.observatory.citizen.domain.ServiceRequestPage;
import es.zaragoza.observatory.citizen.domain.ServiceRequestQuery;
import es.zaragoza.observatory.citizen.domain.ServiceRequestQuery.SortField;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.citizen.domain.ServiceRequestStatus;
import es.zaragoza.observatory.citizen.infrastructure.CitizenProperties;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.AggregationDto;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.ApiItem;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.ApiPage;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.AssignmentDto;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.BucketDto;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.ServiceRequestDto;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.Source;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.SummaryDto;
import es.zaragoza.observatory.citizen.infrastructure.web.CitizenDtos.YearCoverageDto;
import es.zaragoza.observatory.geo.DistrictPopulation;
import es.zaragoza.observatory.geo.DistrictSummary;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;

/**
 * Quejas y sugerencias (SPEC.md §4.7). El backend filtra, ordena, pagina y agrega; el frontend pinta (regla 8).
 * <p>
 * Ninguna respuesta contiene el texto de la queja: no está en la base de datos porque no se pidió al origen
 * (ADR-012). Y ninguna agregación territorial sale sin su denominador y sus registros sin asignar (regla 7).
 */
@RestController
@RequestMapping("/api/v1/citizen")
class CitizenController {

	/** Ordenaciones admitidas del listado (lista blanca explícita, regla 8). */
	static final Map<String, SortField> SORT_FIELDS = Map.of("requestedAt", SortField.REQUESTED_AT, "updatedAt",
			SortField.UPDATED_AT, "id", SortField.ID);

	private final ServiceRequestRepository requests;
	private final Geo geo;
	private final Ingestion ingestion;
	private final Source source;

	CitizenController(ServiceRequestRepository requests, Geo geo, Ingestion ingestion, CitizenProperties properties) {
		this.requests = requests;
		this.geo = geo;
		this.ingestion = ingestion;
		this.source = new Source(CitizenSources.REQUESTS.key(), properties.listUrl().toString());
	}

	@GetMapping("/requests")
	ApiPage<ServiceRequestDto> requests(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size, @RequestParam(defaultValue = "requestedAt,desc") String sort,
			@RequestParam(required = false) Integer district, @RequestParam(required = false) String serviceCode,
			@RequestParam(required = false) String status, @RequestParam(required = false) String assignment,
			@RequestParam(required = false) String internal, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to) {
		ServiceRequestQuery query = query(page, size, sort, district, serviceCode, status, assignment, internal, from,
				to);
		ServiceRequestPage result = requests.search(query);
		Map<Integer, String> names = districtNames();
		List<ServiceRequestDto> items = result.items().stream().map(request -> ServiceRequestDto.of(request, names))
				.toList();
		return new ApiPage<>(source, ingestedAt(), CitizenCaveats.territorial(), result.total(), result.page(),
				result.size(), items);
	}

	/**
	 * Agregación por junta, categoría o mes. En el eje territorial cada grupo lleva su padrón y el año usado, y
	 * la respuesta entera lleva cuántos registros no se han podido asignar: sin eso, un mapa de quejas por junta
	 * es una imagen de dónde hay GPS, no de dónde hay quejas (ADR-011 §7).
	 */
	@GetMapping("/aggregations")
	ApiItem<AggregationDto> aggregations(@RequestParam(defaultValue = "district") String by,
			@RequestParam(required = false) Integer district, @RequestParam(required = false) String serviceCode,
			@RequestParam(required = false) String status, @RequestParam(required = false) String assignment,
			@RequestParam(required = false) String internal, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to) {
		AggregationAxis axis = axis(by);
		ServiceRequestQuery filters = query(0, ServiceRequestQuery.MAX_SIZE, "requestedAt,desc", district, serviceCode,
				status, assignment, internal, from, to).filtersOnly();

		List<AggregationBucket> buckets = requests.aggregate(axis, filters);
		AssignmentCounts counts = requests.assignmentCounts(filters);
		boolean territorial = axis == AggregationAxis.DISTRICT || axis == AggregationAxis.DISTRICT_YEAR;
		Map<Integer, DistrictSummary> districts = territorial ? districtsById() : Map.of();
		// El padrón que acompaña a cada grupo es el de su propio año, no el del año más reciente: usar el último
		// para toda una serie mezcla dos cosas distintas. Los años sin padrón se quedan sin él (ADR-015).
		Map<String, Integer> byYear = axis == AggregationAxis.DISTRICT_YEAR ? populationByYear() : Map.of();

		List<BucketDto> items = buckets.stream().map(bucket -> {
			DistrictSummary summary = territorial ? districts.get(intKey(bucket.key())) : null;
			String label = summary == null ? null : summary.shortName();
			if (axis == AggregationAxis.DISTRICT_YEAR) {
				Integer population = bucket.year() == null ? null : byYear.get(bucket.key() + ":" + bucket.year());
				return BucketDto.of(bucket, label, population, population == null ? null : bucket.year());
			}
			return BucketDto.of(bucket, label, summary == null ? null : summary.population(),
					summary == null ? null : summary.populationYear());
		}).toList();

		// La cobertura por año solo acompaña a la serie: en los demás ejes no hay dos años que comparar.
		List<YearCoverageDto> coverage = axis == AggregationAxis.DISTRICT_YEAR
				? requests.pointCoverageByYear(filters).stream().map(YearCoverageDto::of).toList()
				: List.of();

		long matched = items.stream().mapToLong(BucketDto::total).sum();
		var dto = new AggregationDto(axis.name().toLowerCase(Locale.ROOT), items, coverage, AssignmentDto.of(counts),
				matched, counts.unassigned(), items.stream().mapToLong(BucketDto::internal).sum());
		return new ApiItem<>(source, ingestedAt(),
				axis == AggregationAxis.DISTRICT_YEAR ? CitizenCaveats.series() : CitizenCaveats.aggregations(), dto);
	}

	@GetMapping("/summary")
	ApiItem<SummaryDto> summary() {
		ServiceRequestQuery all = ServiceRequestQuery.all();
		Map<String, Long> byStatus = new LinkedHashMap<>();
		requests.statusCounts(all).forEach((status, n) -> byStatus.put(status.name(), n));
		var dto = new SummaryDto(requests.count(), requests.count(onlyInternal(all)),
				requests.earliestRequestedAt().orElse(null), requests.latestRequestedAt().orElse(null),
				requests.latestUpdatedAt().orElse(null), byStatus,
				AssignmentDto.of(requests.assignmentCounts(all)));
		return new ApiItem<>(source, ingestedAt(), CitizenCaveats.aggregations(), dto);
	}

	// --- traducción de parámetros ----------------------------------------------------------------------

	private ServiceRequestQuery query(int page, int size, String sort, Integer district, String serviceCode,
			String status, String assignment, String internal, Instant from, Instant to) {
		String[] parts = (sort == null ? "requestedAt,desc" : sort).split(",");
		SortField field = SORT_FIELDS.get(parts[0].strip());
		if (field == null) {
			throw badRequest("sort field must be one of " + SORT_FIELDS.keySet().stream().sorted().toList());
		}
		boolean ascending = parts.length > 1 && parts[1].strip().equalsIgnoreCase("asc");
		try {
			return new ServiceRequestQuery(district, blankToNull(serviceCode), enumValue(ServiceRequestStatus.class,
					status, "status"), enumValue(Assignment.class, assignment, "assignment"),
					enumValue(InternalServices.Filter.class, internal, "internal"), from, to, field, ascending, page,
					size);
		}
		catch (IllegalArgumentException ex) {
			throw badRequest(ex.getMessage());
		}
	}

	private AggregationAxis axis(String by) {
		return enumValue(AggregationAxis.class, by, "by");
	}

	private <E extends Enum<E>> E enumValue(Class<E> type, String raw, String parameter) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw badRequest(parameter + " must be one of "
					+ java.util.Arrays.stream(type.getEnumConstants()).map(e -> e.name().toLowerCase(Locale.ROOT))
							.toList());
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static Integer intKey(String key) {
		try {
			return Integer.valueOf(key);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Map<Integer, String> districtNames() {
		return geo.districts().stream()
				.collect(Collectors.toMap(DistrictSummary::id, DistrictSummary::shortName));
	}

	/** Padrón por junta y año, indexado por «junta:año», para casar cada grupo con el denominador de su año. */
	private Map<String, Integer> populationByYear() {
		var byKey = new LinkedHashMap<String, Integer>();
		for (DistrictPopulation record : geo.populations()) {
			byKey.put(record.districtId() + ":" + record.year(), record.population());
		}
		return byKey;
	}

	/** Los mismos filtros pidiendo solo los INTERNAL, para poder contarlos aparte en el resumen (ADR-015). */
	private static ServiceRequestQuery onlyInternal(ServiceRequestQuery query) {
		return new ServiceRequestQuery(query.districtId(), query.serviceCode(), query.status(), query.assignment(),
				InternalServices.Filter.ONLY, query.from(), query.to(), query.sortField(), query.ascending(),
				query.page(), query.size());
	}

	private Map<Integer, DistrictSummary> districtsById() {
		return geo.districts().stream().collect(Collectors.toMap(DistrictSummary::id, Function.identity()));
	}

	private ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	/**
	 * Fin de la última ingesta con éxito. Se toma la más reciente de los dos ejes: lo que un lector quiere saber
	 * es cuándo se miró el origen por última vez, no cuál de los dos recorridos fue.
	 */
	private Instant ingestedAt() {
		Instant requestsRun = lastRun(CitizenSources.REQUESTS);
		Instant closuresRun = lastRun(CitizenSources.CLOSURES);
		if (requestsRun == null) {
			return closuresRun;
		}
		if (closuresRun == null) {
			return requestsRun;
		}
		return requestsRun.isAfter(closuresRun) ? requestsRun : closuresRun;
	}

	private Instant lastRun(es.zaragoza.observatory.shared.DatasetRef dataset) {
		return ingestion.lastSuccessful(dataset).map(IngestionRunSummary::finishedAt).orElse(null);
	}

}
