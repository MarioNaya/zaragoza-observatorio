package es.zaragoza.observatory.spending.infrastructure.web;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
import es.zaragoza.observatory.spending.domain.GrantAxis;
import es.zaragoza.observatory.spending.domain.GrantQuery;
import es.zaragoza.observatory.spending.domain.GrantQuery.SortField;
import es.zaragoza.observatory.spending.domain.GrantRead;
import es.zaragoza.observatory.spending.domain.GrantRepository;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;
import es.zaragoza.observatory.spending.infrastructure.web.GrantDtos.AggregationDto;
import es.zaragoza.observatory.spending.infrastructure.web.GrantDtos.BucketDto;
import es.zaragoza.observatory.spending.infrastructure.web.GrantDtos.CallDto;
import es.zaragoza.observatory.spending.infrastructure.web.GrantDtos.GrantDto;
import es.zaragoza.observatory.spending.infrastructure.web.GrantDtos.GrantPage;
import es.zaragoza.observatory.spending.infrastructure.web.GrantDtos.SummaryDto;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.ApiItem;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.ApiPage;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.Source;

/**
 * Subvenciones (SPEC.md §4.7, S3.3, ADR-018). El backend filtra, ordena, pagina y agrega; el frontend pinta
 * (regla 8).
 * <p>
 * Cuelga de {@code /spending/grants} y no se mezcla con los otros dos recursos del módulo porque <b>no cuentan lo
 * mismo</b>: {@code /processes} cuenta procesos de contratación, {@code /budget} partidas presupuestarias y este
 * concesiones. Cada respuesta declara su unidad.
 * <p>
 * El parámetro {@code naturalPerson} es la misma figura que el {@code internal} de las quejas (ADR-015): por
 * defecto no quita nada y ninguna cifra cambia de valor, pero deja separarlas a quien lee.
 */
@RestController
@RequestMapping("/api/v1/spending/grants")
class GrantController {

	/** Ordenaciones admitidas del listado (lista blanca explícita, regla 8). */
	static final List<String> SORT_FIELDS = List.of("id", "granted", "requested", "grantedOn", "requestedOn");

	private final GrantRepository grants;
	private final Ingestion ingestion;
	private final Source source;

	GrantController(GrantRepository grants, Ingestion ingestion, SpendingProperties properties) {
		this.grants = grants;
		this.ingestion = ingestion;
		this.source = new Source(SpendingSources.GRANTS.key(), properties.grants().grantsUrl().toString());
	}

	@GetMapping
	GrantPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "grantedOn,desc") String sort,
			@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer call,
			@RequestParam(required = false) String beneficiary,
			@RequestParam(required = false) String classification,
			@RequestParam(required = false) Boolean naturalPerson, @RequestParam(required = false) String q) {
		GrantRead.Page result = grants
				.search(query(page, size, sort, year, call, beneficiary, classification, naturalPerson, q));
		return new GrantPage(source, ingestedAt(), GrantCaveats.base(), "concesiones", result.total(),
				result.page(), result.size(), result.items().stream().map(GrantDto::of).toList());
	}

	@GetMapping("/{id}")
	ApiItem<GrantDto> grant(@PathVariable long id) {
		return grants.grant(id)
				.map(found -> new ApiItem<>(source, ingestedAt(), GrantCaveats.base(), GrantDto.of(found)))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"no hay ninguna concesión con identificador " + id));
	}

	/** Las convocatorias, que son la unidad de la que cuelgan las concesiones. Unidad distinta, recurso aparte. */
	@GetMapping("/calls")
	ApiPage<CallDto> calls() {
		List<CallDto> items = grants.calls().stream().map(CallDto::of).toList();
		return new ApiPage<>(source, ingestedAt(), GrantCaveats.base(), items.size(), 0, items.size(), items);
	}

	@GetMapping("/calls/{id}")
	ApiItem<CallDto> call(@PathVariable int id) {
		return grants.call(id)
				.map(found -> new ApiItem<>(source, ingestedAt(), GrantCaveats.base(), CallDto.of(found)))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"no hay ninguna convocatoria con identificador " + id));
	}

	/** Agregación por año, convocatoria, línea, tipo, gestor, clasificación o beneficiario. */
	@GetMapping("/aggregations")
	ApiItem<AggregationDto> aggregations(@RequestParam(defaultValue = "year") String by,
			@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer call,
			@RequestParam(required = false) String beneficiary,
			@RequestParam(required = false) String classification,
			@RequestParam(required = false) Boolean naturalPerson, @RequestParam(required = false) String q) {
		GrantAxis axis = axis(by);
		GrantQuery filters = query(0, GrantQuery.MAX_SIZE, "grantedOn,desc", year, call, beneficiary,
				classification, naturalPerson, q).filtersOnly();
		List<BucketDto> buckets = grants.aggregate(axis, filters).stream().map(BucketDto::of).toList();
		return new ApiItem<>(source, ingestedAt(), GrantCaveats.aggregation(), AggregationDto.of(axis, buckets));
	}

	@GetMapping("/summary")
	ApiItem<SummaryDto> summary() {
		return new ApiItem<>(source, ingestedAt(), GrantCaveats.base(), SummaryDto.of(grants.totals()));
	}

	// --- traducción de parámetros ----------------------------------------------------------------------

	private GrantQuery query(int page, int size, String sort, Integer year, Integer call, String beneficiary,
			String classification, Boolean naturalPerson, String titleContains) {
		String[] parts = (sort == null ? "grantedOn,desc" : sort).split(",");
		String field = parts[0].strip();
		if (!SORT_FIELDS.contains(field)) {
			throw badRequest("sort field must be one of " + SORT_FIELDS);
		}
		boolean ascending = parts.length > 1 && parts[1].strip().equalsIgnoreCase("asc");
		try {
			return new GrantQuery(year, call, blankToNull(beneficiary), blankToNull(classification), naturalPerson,
					blankToNull(titleContains), sortField(field), ascending, page, size);
		}
		catch (IllegalArgumentException ex) {
			throw badRequest(ex.getMessage());
		}
	}

	private static SortField sortField(String field) {
		return switch (field) {
			case "id" -> SortField.ID;
			case "granted" -> SortField.GRANTED;
			case "requested" -> SortField.REQUESTED;
			case "requestedOn" -> SortField.REQUESTED_ON;
			default -> SortField.GRANTED_ON;
		};
	}

	private GrantAxis axis(String by) {
		if (by == null || by.isBlank()) {
			return GrantAxis.YEAR;
		}
		try {
			return GrantAxis.valueOf(by.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw badRequest("by must be one of "
					+ Arrays.stream(GrantAxis.values()).map(a -> a.name().toLowerCase(Locale.ROOT)).toList());
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	/** Fin de la última ingesta de concesiones con éxito. Los otros tres recursos tienen la suya. */
	private Instant ingestedAt() {
		return ingestion.lastSuccessful(SpendingSources.GRANTS).map(IngestionRunSummary::finishedAt).orElse(null);
	}

}
