package es.zaragoza.observatory.spending.infrastructure.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.spending.SpendingSources;
import es.zaragoza.observatory.spending.domain.BudgetAxis;
import es.zaragoza.observatory.spending.domain.BudgetBucket;
import es.zaragoza.observatory.spending.domain.BudgetLinePage;
import es.zaragoza.observatory.spending.domain.BudgetQuery;
import es.zaragoza.observatory.spending.domain.BudgetQuery.SortField;
import es.zaragoza.observatory.spending.domain.BudgetRepository;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;
import es.zaragoza.observatory.spending.infrastructure.web.BudgetDtos.AggregationDto;
import es.zaragoza.observatory.spending.infrastructure.web.BudgetDtos.BucketDto;
import es.zaragoza.observatory.spending.infrastructure.web.BudgetDtos.LineDto;
import es.zaragoza.observatory.spending.infrastructure.web.BudgetDtos.LinePage;
import es.zaragoza.observatory.spending.infrastructure.web.BudgetDtos.SnapshotDto;
import es.zaragoza.observatory.spending.infrastructure.web.BudgetDtos.SummaryDto;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.ApiItem;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.ApiPage;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.Source;

/**
 * Presupuesto de gastos (SPEC.md §4.7, S3.2). El backend filtra, ordena, pagina y agrega; el frontend pinta
 * (regla 8).
 * <p>
 * Cuelga de {@code /spending/budget} y no se mezcla con {@code /spending/processes} porque <b>no cuentan lo
 * mismo</b>: allí procesos de contratación, aquí partidas presupuestarias, y cada respuesta declara su unidad.
 * Es además el único recurso del producto con dinero <b>pagado</b>.
 * <p>
 * El listado y las agregaciones que no son la serie describen <b>una</b> instantánea: sin eso, una suma
 * mezclaría 140 fotos acumuladas y contaría el mismo euro muchas veces. Cuando no se pide fecha se contesta la
 * más reciente cargada, y la respuesta la declara.
 */
@RestController
@RequestMapping("/api/v1/spending/budget")
class BudgetController {

	/** Ordenaciones admitidas del listado (lista blanca explícita, regla 8). */
	static final List<String> SORT_FIELDS = List.of("concept", "creditFinal", "committed", "obligations",
			"payments");

	private final BudgetRepository budget;
	private final Ingestion ingestion;
	private final Source source;

	BudgetController(BudgetRepository budget, Ingestion ingestion, SpendingProperties properties) {
		this.budget = budget;
		this.ingestion = ingestion;
		this.source = new Source(SpendingSources.BUDGET.key(), properties.budget().censusUrl().toString());
	}

	/** El censo entero con su situación de lectura y los totales de cada foto: es la serie del producto. */
	@GetMapping("/snapshots")
	ApiPage<SnapshotDto> snapshots() {
		List<SnapshotDto> items = budget.snapshots().stream().map(SnapshotDto::of).toList();
		return new ApiPage<>(source, ingestedAt(), BudgetCaveats.base(), items.size(), 0, items.size(), items);
	}

	@GetMapping("/snapshots/{date}")
	ApiItem<SnapshotDto> snapshot(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return budget.snapshot(date)
				.map(found -> new ApiItem<>(source, ingestedAt(), BudgetCaveats.base(), SnapshotDto.of(found)))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"el censo no publica ninguna instantánea con fecha " + date));
	}

	@GetMapping("/lines")
	LinePage lines(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "obligations,desc") String sort,
			@RequestParam(required = false) Integer chapter, @RequestParam(required = false) String area,
			@RequestParam(required = false) String programme, @RequestParam(required = false) String organ,
			@RequestParam(required = false) String q) {
		BudgetLinePage result = budget.searchLines(query(date, page, size, sort, chapter, area, programme, organ, q));
		return new LinePage(source, ingestedAt(), BudgetCaveats.base(), result.snapshotDate(), result.total(),
				result.page(), result.size(), result.items().stream().map(LineDto::of).toList());
	}

	/**
	 * Agregación por año, capítulo, área, programa u órgano. La respuesta declara en {@code basis} de dónde salen
	 * los grupos: el eje por año recorre la serie tomando la última instantánea de cada ejercicio, y los demás
	 * agregan dentro de una sola foto.
	 */
	@GetMapping("/aggregations")
	ApiItem<AggregationDto> aggregations(@RequestParam(defaultValue = "year") String by,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) Integer chapter, @RequestParam(required = false) String area,
			@RequestParam(required = false) String programme, @RequestParam(required = false) String organ,
			@RequestParam(required = false) String q) {
		BudgetAxis axis = axis(by);
		BudgetQuery filters = query(date, 0, BudgetQuery.MAX_SIZE, "obligations,desc", chapter, area, programme,
				organ, q).filtersOnly();
		List<BudgetBucket> buckets = budget.aggregate(axis, filters);
		List<BucketDto> items = buckets.stream().map(BucketDto::of).toList();
		LocalDate snapshotDate = axis == BudgetAxis.YEAR ? null
				: items.stream().map(BucketDto::snapshotDate).findFirst()
						.orElse(filters.snapshotDate() != null ? filters.snapshotDate()
								: budget.latestLoaded().orElse(null));
		return new ApiItem<>(source, ingestedAt(), BudgetCaveats.aggregation(axis == BudgetAxis.YEAR),
				AggregationDto.of(axis, snapshotDate, items));
	}

	@GetMapping("/summary")
	ApiItem<SummaryDto> summary() {
		return new ApiItem<>(source, ingestedAt(), BudgetCaveats.base(), SummaryDto.of(budget.totals()));
	}

	// --- traducción de parámetros ----------------------------------------------------------------------

	private BudgetQuery query(LocalDate date, int page, int size, String sort, Integer chapter, String area,
			String programme, String organ, String headingContains) {
		String[] parts = (sort == null ? "obligations,desc" : sort).split(",");
		String field = parts[0].strip();
		if (!SORT_FIELDS.contains(field)) {
			throw badRequest("sort field must be one of " + SORT_FIELDS);
		}
		boolean ascending = parts.length > 1 && parts[1].strip().equalsIgnoreCase("asc");
		try {
			return new BudgetQuery(date, chapter, blankToNull(area), blankToNull(programme), blankToNull(organ),
					blankToNull(headingContains), sortField(field), ascending, page, size);
		}
		catch (IllegalArgumentException ex) {
			throw badRequest(ex.getMessage());
		}
	}

	private static SortField sortField(String field) {
		return switch (field) {
			case "concept" -> SortField.CONCEPT;
			case "creditFinal" -> SortField.CREDIT_FINAL;
			case "committed" -> SortField.COMMITTED;
			case "payments" -> SortField.PAYMENTS;
			default -> SortField.OBLIGATIONS;
		};
	}

	private BudgetAxis axis(String by) {
		if (by == null || by.isBlank()) {
			return BudgetAxis.YEAR;
		}
		try {
			return BudgetAxis.valueOf(by.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw badRequest("by must be one of "
					+ Arrays.stream(BudgetAxis.values()).map(a -> a.name().toLowerCase(Locale.ROOT)).toList());
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	/**
	 * Fin del último censo con éxito. <b>No es la fecha del contenido</b>: las partidas de cada instantánea se
	 * leen por lotes después, y cada instantánea trae su propio {@code lastSeenAt}.
	 */
	private Instant ingestedAt() {
		return ingestion.lastSuccessful(SpendingSources.BUDGET).map(IngestionRunSummary::finishedAt).orElse(null);
	}

}
