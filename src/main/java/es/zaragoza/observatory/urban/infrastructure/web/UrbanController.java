package es.zaragoza.observatory.urban.infrastructure.web;

import java.time.Instant;
import java.util.Arrays;
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

import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.geo.DistrictPopulation;
import es.zaragoza.observatory.geo.DistrictSummary;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.urban.UrbanSources;
import es.zaragoza.observatory.urban.domain.AggregationAxis;
import es.zaragoza.observatory.urban.domain.AggregationBucket;
import es.zaragoza.observatory.urban.domain.PremisesPage;
import es.zaragoza.observatory.urban.domain.PremisesQuery;
import es.zaragoza.observatory.urban.domain.PremisesQuery.SortField;
import es.zaragoza.observatory.urban.domain.PremisesRepository;
import es.zaragoza.observatory.urban.infrastructure.UrbanProperties;
import es.zaragoza.observatory.urban.infrastructure.web.UrbanDtos.AggregationDto;
import es.zaragoza.observatory.urban.infrastructure.web.UrbanDtos.ApiItem;
import es.zaragoza.observatory.urban.infrastructure.web.UrbanDtos.ApiPage;
import es.zaragoza.observatory.urban.infrastructure.web.UrbanDtos.BucketDto;
import es.zaragoza.observatory.urban.infrastructure.web.UrbanDtos.PremisesDto;
import es.zaragoza.observatory.urban.infrastructure.web.UrbanDtos.Source;
import es.zaragoza.observatory.urban.infrastructure.web.UrbanDtos.SummaryDto;
import es.zaragoza.observatory.urban.infrastructure.web.UrbanDtos.YearCoverageDto;

/**
 * Locales con licencia (SPEC.md §4.7). El backend filtra, ordena, pagina y agrega; el frontend pinta (regla 8).
 * <p>
 * Ninguna respuesta contiene texto libre: no está en la base de datos porque no se guarda (ADR-016 §3). Y
 * ninguna agregación territorial sale sin su denominador y sus registros sin asignar (regla 7).
 */
@RestController
@RequestMapping("/api/v1/urban")
class UrbanController {

	/** Ordenaciones admitidas del listado (lista blanca explícita, regla 8). */
	static final Map<String, SortField> SORT_FIELDS = Map.of("createdAt", SortField.CREATED_AT, "updatedAt",
			SortField.UPDATED_AT, "id", SortField.ID);

	private final PremisesRepository premises;
	private final Geo geo;
	private final Ingestion ingestion;
	private final Source source;

	UrbanController(PremisesRepository premises, Geo geo, Ingestion ingestion, UrbanProperties properties) {
		this.premises = premises;
		this.geo = geo;
		this.ingestion = ingestion;
		this.source = new Source(UrbanSources.PREMISES.key(), properties.listUrl().toString());
	}

	@GetMapping("/premises")
	ApiPage<PremisesDto> premises(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size, @RequestParam(defaultValue = "createdAt,desc") String sort,
			@RequestParam(required = false) Integer district, @RequestParam(required = false) String iae,
			@RequestParam(required = false) Integer iaeSection, @RequestParam(required = false) Integer iaeGroup,
			@RequestParam(required = false) Integer statusCode, @RequestParam(required = false) String zone,
			@RequestParam(required = false) String assignment, @RequestParam(required = false) Integer licenceYear,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		PremisesQuery query = query(page, size, sort, district, iae, iaeSection, iaeGroup, statusCode, zone,
				assignment, licenceYear, from, to);
		PremisesPage result = premises.search(query);
		Map<Integer, String> names = districtNames();
		List<PremisesDto> items = result.items().stream().map(item -> PremisesDto.of(item, names)).toList();
		return new ApiPage<>(source, ingestedAt(), UrbanCaveats.territorial(), result.total(), result.page(),
				result.size(), items);
	}

	/**
	 * Agregación por junta, actividad, estado, año de licencia o tipo. Cada respuesta declara en {@code unit} si
	 * cuenta locales o licencias, porque no son lo mismo y la cifra no dice cuál es. En el eje territorial cada
	 * grupo lleva su padrón y el año usado, y la respuesta entera lleva cuántos registros no se han podido
	 * asignar: sin eso, un mapa de locales por junta es una imagen de dónde hay coordenada (ADR-011 §7).
	 */
	@GetMapping("/aggregations")
	ApiItem<AggregationDto> aggregations(@RequestParam(defaultValue = "district") String by,
			@RequestParam(required = false) Integer district, @RequestParam(required = false) String iae,
			@RequestParam(required = false) Integer iaeSection, @RequestParam(required = false) Integer iaeGroup,
			@RequestParam(required = false) Integer statusCode, @RequestParam(required = false) String zone,
			@RequestParam(required = false) String assignment, @RequestParam(required = false) Integer licenceYear,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		AggregationAxis axis = enumValue(AggregationAxis.class, by, "by");
		if (axis == null) {
			axis = AggregationAxis.DISTRICT;
		}
		PremisesQuery filters = query(0, PremisesQuery.MAX_SIZE, "createdAt,desc", district, iae, iaeSection,
				iaeGroup, statusCode, zone, assignment, licenceYear, from, to).filtersOnly();

		List<AggregationBucket> buckets = premises.aggregate(axis, filters);
		Map<Assignment, Long> counts = premises.assignmentCounts(filters);
		Map<Integer, DistrictSummary> districts = axis.isTerritorial() ? districtsById() : Map.of();
		// El padrón que acompaña a cada grupo es el de su propio año, no el del año más reciente: usar el último
		// para toda una serie mezcla dos cosas distintas. Los años sin padrón se quedan sin él (ADR-015).
		Map<String, Integer> byYear = axis == AggregationAxis.DISTRICT_LICENCE_YEAR ? populationByYear() : Map.of();

		AggregationAxis chosen = axis;
		List<BucketDto> items = buckets.stream().map(bucket -> {
			DistrictSummary summary = chosen.isTerritorial() ? districts.get(intKey(bucket.key())) : null;
			String label = summary == null ? null : summary.shortName();
			if (chosen == AggregationAxis.DISTRICT_LICENCE_YEAR) {
				Integer population = bucket.year() == null ? null : byYear.get(bucket.key() + ":" + bucket.year());
				return BucketDto.of(bucket, label, population, population == null ? null : bucket.year());
			}
			return BucketDto.of(bucket, label, summary == null ? null : summary.population(),
					summary == null ? null : summary.populationYear());
		}).toList();

		// La cobertura por año solo acompaña a las series: en los demás ejes no hay dos años que comparar.
		boolean series = axis == AggregationAxis.DISTRICT_LICENCE_YEAR || axis == AggregationAxis.LICENCE_YEAR;
		List<YearCoverageDto> coverage = series
				? premises.pointCoverageByLicenceYear(filters).stream().map(YearCoverageDto::of).toList()
				: List.of();

		long matched = items.stream().mapToLong(BucketDto::total).sum();
		long unassigned = counts.getOrDefault(Assignment.NO_POINT, 0L)
				+ counts.getOrDefault(Assignment.OUTSIDE, 0L);
		var dto = new AggregationDto(axis.name().toLowerCase(Locale.ROOT),
				axis.unit().name().toLowerCase(Locale.ROOT), items, coverage, UrbanDtos.assignmentMap(counts),
				matched, unassigned);
		return new ApiItem<>(source, ingestedAt(),
				series ? UrbanCaveats.series() : UrbanCaveats.territorial(), dto);
	}

	@GetMapping("/summary")
	ApiItem<SummaryDto> summary() {
		PremisesQuery all = PremisesQuery.all();
		Map<String, Long> byStatus = new LinkedHashMap<>();
		premises.statusCounts(all).forEach((code, n) -> byStatus.put(String.valueOf(code), n));
		var dto = new SummaryDto(premises.count(), premises.countLicences(),
				premises.earliestCreatedAt().orElse(null), premises.latestUpdatedAt().orElse(null), byStatus,
				UrbanDtos.assignmentMap(premises.assignmentCounts(all)));
		return new ApiItem<>(source, ingestedAt(), UrbanCaveats.base(), dto);
	}

	// --- traducción de parámetros ----------------------------------------------------------------------

	private PremisesQuery query(int page, int size, String sort, Integer district, String iae, Integer iaeSection,
			Integer iaeGroup, Integer statusCode, String zone, String assignment, Integer licenceYear, Instant from,
			Instant to) {
		String[] parts = (sort == null ? "createdAt,desc" : sort).split(",");
		SortField field = SORT_FIELDS.get(parts[0].strip());
		if (field == null) {
			throw badRequest("sort field must be one of " + SORT_FIELDS.keySet().stream().sorted().toList());
		}
		boolean ascending = parts.length > 1 && parts[1].strip().equalsIgnoreCase("asc");
		try {
			return new PremisesQuery(district, blankToNull(iae), iaeSection, iaeGroup, statusCode, blankToNull(zone),
					enumValue(Assignment.class, assignment, "assignment"), licenceYear, from, to, field, ascending,
					page, size);
		}
		catch (IllegalArgumentException ex) {
			throw badRequest(ex.getMessage());
		}
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
					+ Arrays.stream(type.getEnumConstants()).map(e -> e.name().toLowerCase(Locale.ROOT)).toList());
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
		return geo.districts().stream().collect(Collectors.toMap(DistrictSummary::id, DistrictSummary::shortName));
	}

	/** Padrón por junta y año, indexado por «junta:año», para casar cada grupo con el denominador de su año. */
	private Map<String, Integer> populationByYear() {
		var byKey = new LinkedHashMap<String, Integer>();
		for (DistrictPopulation record : geo.populations()) {
			byKey.put(record.districtId() + ":" + record.year(), record.population());
		}
		return byKey;
	}

	private Map<Integer, DistrictSummary> districtsById() {
		return geo.districts().stream().collect(Collectors.toMap(DistrictSummary::id, Function.identity()));
	}

	private ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	/** Fin de la última ingesta con éxito. Un solo eje, así que una sola fecha (ADR-016 §4). */
	private Instant ingestedAt() {
		return ingestion.lastSuccessful(UrbanSources.PREMISES).map(IngestionRunSummary::finishedAt).orElse(null);
	}

}
