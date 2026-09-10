package es.zaragoza.observatory.spending.infrastructure.web;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import es.zaragoza.observatory.spending.domain.AggregationAxis;
import es.zaragoza.observatory.spending.domain.AggregationBucket;
import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;
import es.zaragoza.observatory.spending.domain.ProcessPage;
import es.zaragoza.observatory.spending.domain.ProcessQuery;
import es.zaragoza.observatory.spending.domain.ProcessQuery.SortField;
import es.zaragoza.observatory.spending.domain.ReleaseStatus;
import es.zaragoza.observatory.spending.domain.SpendingTotals;
import es.zaragoza.observatory.spending.domain.Stage;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.AggregationDto;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.ApiItem;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.ApiPage;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.BucketDto;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.ProcessDto;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.Source;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.SummaryDto;

/**
 * Contratación pública (SPEC.md §4.7). El backend filtra, ordena, pagina y agrega; el frontend pinta (regla 8).
 * <p>
 * El recurso se llama {@code processes} y no {@code contracts} porque lo que hay son procesos de contratación:
 * 2.379 no tienen release, 1.560 tienen un contrato sin fecha de firma y 305 son licitaciones vivas (ADR-017 §9).
 * <p>
 * <b>Ningún parámetro es territorial y no puede haberlo</b>: esta fuente no publica localización, medido sobre
 * los 5.622 documentos. La ausencia se dice en {@code caveats} (ADR-003 §4).
 */
@RestController
@RequestMapping("/api/v1/spending")
class SpendingController {

	/** Ordenaciones admitidas del listado (lista blanca explícita, regla 8). */
	static final Map<String, SortField> SORT_FIELDS = Map.of("publishedAt", SortField.PUBLISHED_AT,
			"tenderAmount", SortField.TENDER_AMOUNT, "awardedAmount", SortField.AWARDED_AMOUNT, "fileNumber",
			SortField.FILE_NUMBER);

	private final ContractingProcessRepository processes;
	private final Ingestion ingestion;
	private final Source source;

	SpendingController(ContractingProcessRepository processes, Ingestion ingestion,
			SpendingProperties properties) {
		this.processes = processes;
		this.ingestion = ingestion;
		this.source = new Source(SpendingSources.PROCESSES.key(), properties.listUrl().toString());
	}

	@GetMapping("/processes")
	ApiPage<ProcessDto> processes(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "publishedAt,desc") String sort,
			@RequestParam(required = false) Integer year, @RequestParam(required = false) String tenderStatus,
			@RequestParam(required = false) String method, @RequestParam(required = false) String category,
			@RequestParam(required = false) String stage, @RequestParam(required = false) String releaseStatus,
			@RequestParam(required = false) String cpv, @RequestParam(required = false) String taxId,
			@RequestParam(required = false) String procuringEntity,
			@RequestParam(required = false) Boolean inDocumentedList, @RequestParam(required = false) String q,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		ProcessQuery query = query(page, size, sort, year, tenderStatus, method, category, stage, releaseStatus,
				cpv, taxId, procuringEntity, inDocumentedList, q, from, to);
		ProcessPage result = processes.search(query);
		return new ApiPage<>(source, ingestedAt(), SpendingCaveats.base(), result.total(), result.page(),
				result.size(), result.items().stream().map(ProcessDto::of).toList());
	}

	@GetMapping("/processes/{ocid}")
	ApiItem<ProcessDto> process(@PathVariable String ocid) {
		return processes.byOcid(ocid)
				.map(found -> new ApiItem<>(source, ingestedAt(), SpendingCaveats.base(), ProcessDto.of(found)))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"no hay ningún proceso con ocid " + ocid));
	}

	/**
	 * Agregación por año, etiqueta, estado, procedimiento, categoría, etapa, órgano, situación del detalle, CPV o
	 * adjudicataria. Cada respuesta declara en {@code unit} si cuenta procesos o adjudicaciones, y si sus grupos
	 * se solapan; sin eso, la suma de los grupos parece el total y en dos ejes no lo es (ADR-017 §7).
	 */
	@GetMapping("/aggregations")
	ApiItem<AggregationDto> aggregations(@RequestParam(defaultValue = "year") String by,
			@RequestParam(required = false) Integer year, @RequestParam(required = false) String tenderStatus,
			@RequestParam(required = false) String method, @RequestParam(required = false) String category,
			@RequestParam(required = false) String stage, @RequestParam(required = false) String releaseStatus,
			@RequestParam(required = false) String cpv, @RequestParam(required = false) String taxId,
			@RequestParam(required = false) String procuringEntity,
			@RequestParam(required = false) Boolean inDocumentedList, @RequestParam(required = false) String q,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		AggregationAxis axis = enumValue(AggregationAxis.class, by, "by");
		if (axis == null) {
			axis = AggregationAxis.YEAR;
		}
		ProcessQuery filters = query(0, ProcessQuery.MAX_SIZE, "publishedAt,desc", year, tenderStatus, method,
				category, stage, releaseStatus, cpv, taxId, procuringEntity, inDocumentedList, q, from, to)
				.filtersOnly();

		AggregationAxis chosen = axis;
		List<AggregationBucket> buckets = processes.aggregate(axis, filters);
		List<BucketDto> items = buckets.stream().map(bucket -> BucketDto.of(bucket, chosen)).toList();
		SpendingTotals totals = processes.totals(filters);
		long matched = items.stream().mapToLong(BucketDto::total).sum();
		long withoutRelease = totals.processes() - totals.byReleaseStatus()
				.getOrDefault(ReleaseStatus.PUBLISHED, 0L);

		var dto = new AggregationDto(axis.name().toLowerCase(Locale.ROOT),
				axis.unit().name().toLowerCase(Locale.ROOT), axis.isOverlapping(), items, matched,
				totals.processes(), withoutRelease);
		return new ApiItem<>(source, ingestedAt(),
				SpendingCaveats.aggregation(axis.isOverlapping(), axis == AggregationAxis.YEAR), dto);
	}

	@GetMapping("/summary")
	ApiItem<SummaryDto> summary() {
		return new ApiItem<>(source, ingestedAt(), SpendingCaveats.base(),
				SummaryDto.of(processes.totals(ProcessQuery.all())));
	}

	// --- traducción de parámetros ----------------------------------------------------------------------

	private ProcessQuery query(int page, int size, String sort, Integer year, String tenderStatus, String method,
			String category, String stage, String releaseStatus, String cpv, String taxId, String procuringEntity,
			Boolean inDocumentedList, String titleContains, Instant from, Instant to) {
		String[] parts = (sort == null ? "publishedAt,desc" : sort).split(",");
		SortField field = SORT_FIELDS.get(parts[0].strip());
		if (field == null) {
			throw badRequest("sort field must be one of " + SORT_FIELDS.keySet().stream().sorted().toList());
		}
		boolean ascending = parts.length > 1 && parts[1].strip().equalsIgnoreCase("asc");
		try {
			return new ProcessQuery(year, blankToNull(tenderStatus), blankToNull(method), blankToNull(category),
					enumValue(Stage.class, stage, "stage"),
					enumValue(ReleaseStatus.class, releaseStatus, "releaseStatus"), blankToNull(cpv),
					blankToNull(taxId), blankToNull(procuringEntity), inDocumentedList, blankToNull(titleContains),
					from, to, field, ascending, page, size);
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

	private ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	/**
	 * Fin del último censo con éxito. <b>No es la fecha del contenido</b>: el detalle de cada proceso se lee por
	 * lotes después, y cada proceso trae su propio {@code lastSeenAt}.
	 */
	private Instant ingestedAt() {
		return ingestion.lastSuccessful(SpendingSources.PROCESSES).map(IngestionRunSummary::finishedAt)
				.orElse(null);
	}

}
