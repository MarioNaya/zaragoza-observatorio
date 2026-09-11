package es.zaragoza.observatory.territory.infrastructure.web;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import es.zaragoza.observatory.geo.DistrictSummary;
import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.territory.application.ComposeCrossTab;
import es.zaragoza.observatory.territory.domain.CrossTab;
import es.zaragoza.observatory.territory.domain.CrossTabSort;
import es.zaragoza.observatory.territory.domain.Denominator;
import es.zaragoza.observatory.territory.domain.Measure;
import es.zaragoza.observatory.territory.infrastructure.web.TerritoryDtos.ApiItem;
import es.zaragoza.observatory.territory.infrastructure.web.TerritoryDtos.CrossTabDto;
import es.zaragoza.observatory.territory.infrastructure.web.TerritoryDtos.DistrictCardDto;
import es.zaragoza.observatory.territory.infrastructure.web.TerritoryDtos.MeasureDto;
import es.zaragoza.observatory.territory.infrastructure.web.TerritoryDtos.PopulationDto;
import es.zaragoza.observatory.territory.infrastructure.web.TerritoryDtos.RowDto;
import es.zaragoza.observatory.territory.infrastructure.web.TerritoryDtos.WindowDto;

/**
 * El cruce territorial (ADR-019). El backend compone, ordena y agrega; el frontend pinta (regla 8).
 * <p>
 * Dos recursos y ninguna dimensión que no sea la junta: la matriz de las 29 juntas por las medidas pedidas, y la
 * ficha de una junta. No hay vistas por tema y no las habrá: cada vista sería una pregunta ya elegida, y elegir
 * la pregunta es la voz del producto que la regla 6 limita.
 */
@RestController
@RequestMapping("/api/v1/territory")
class TerritoryController {

	private static final String DEFAULT_SORT = "district,asc";

	private final ComposeCrossTab crossTab;

	TerritoryController(ComposeCrossTab crossTab) {
		this.crossTab = crossTab;
	}

	/**
	 * La matriz: una fila por junta y una columna por medida. Sin {@code measures} salen todas las del catálogo,
	 * que no es elegir un cruce sino no elegir ninguno.
	 */
	@GetMapping("/districts")
	ApiItem<CrossTabDto> districts(@RequestParam(required = false) String measures,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(required = false) String denominator,
			@RequestParam(required = false) Integer populationYear,
			@RequestParam(defaultValue = DEFAULT_SORT) String sort) {
		List<Measure> wanted = measures(measures);
		CrossTab tab = crossTab.compose(wanted, window(from, to), denominator(denominator), populationYear,
				sort(sort, wanted));
		return new ApiItem<>(TerritoryCaveats.of(tab), CrossTabDto.of(tab, wanted, sort));
	}

	/**
	 * La ficha de una junta: la misma fila del cruce, con la serie de padrón entera al lado. El 404 es de la
	 * junta, no de la medida: una junta sin registros existe y sale con ceros.
	 */
	@GetMapping("/districts/{districtId}")
	ApiItem<DistrictCardDto> district(@PathVariable int districtId,
			@RequestParam(required = false) String measures, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(required = false) String denominator,
			@RequestParam(required = false) Integer populationYear) {
		DistrictSummary district = crossTab.districts().stream().filter(d -> d.id() == districtId).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"no hay ninguna junta con id " + districtId));

		List<Measure> wanted = measures(measures);
		CrossTab tab = crossTab.compose(wanted, window(from, to), denominator(denominator), populationYear,
				CrossTabSort.byDistrict());
		CrossTab.DistrictRow row = tab.rows().stream().filter(r -> r.districtId() == district.id()).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"no hay ninguna junta con id " + districtId));

		List<MeasureDto> columns = tab.columns().stream().map(MeasureDto::of).toList();
		List<PopulationDto> series = crossTab.populationSeries(district.id()).stream()
				.map(record -> new PopulationDto(record.year(), record.population())).toList();
		var card = new DistrictCardDto(WindowDto.of(tab.window()),
				tab.denominator().name().toLowerCase(Locale.ROOT), tab.populationYear(), columns,
				RowDto.of(row, wanted), series);
		return new ApiItem<>(TerritoryCaveats.of(tab), card);
	}

	// --- traducción de parámetros ----------------------------------------------------------------------

	/**
	 * Lista blanca cerrada (ADR-019 §2). Un identificador de un módulo sin eje territorial se rechaza diciendo
	 * <b>por qué</b>, no con un «medida desconocida»: la ausencia de gasto por junta es un hecho del dato abierto
	 * y se afirma (ADR-019 §8).
	 */
	private List<Measure> measures(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of(Measure.values());
		}
		var wanted = new LinkedHashSet<Measure>();
		for (String token : raw.split(",")) {
			String id = token.strip();
			if (id.isEmpty()) {
				continue;
			}
			Optional<Measure> measure = Measure.byId(id);
			if (measure.isEmpty()) {
				String module = Measure.moduleOf(id);
				throw badRequest(Measure.MODULES_WITHOUT_TERRITORY.contains(module)
						? TerritoryCaveats.noTerritorialAxis(module)
						: "measures must be a comma-separated subset of " + Measure.ids() + ", got '" + id + "'");
			}
			wanted.add(measure.get());
		}
		if (wanted.isEmpty()) {
			throw badRequest("measures must name at least one of " + Measure.ids());
		}
		return List.copyOf(wanted);
	}

	private DateWindow window(Instant from, Instant to) {
		try {
			return new DateWindow(from, to);
		}
		catch (IllegalArgumentException ex) {
			throw badRequest(ex.getMessage());
		}
	}

	private Denominator denominator(String raw) {
		if (raw == null || raw.isBlank()) {
			return Denominator.POPULATION;
		}
		try {
			return Denominator.valueOf(raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw badRequest("denominator must be one of [population, none]");
		}
	}

	/** Solo por la junta o por una medida <b>pedida</b>: ordenar por una columna ausente no se puede comprobar. */
	private CrossTabSort sort(String raw, List<Measure> wanted) {
		String[] parts = (raw == null || raw.isBlank() ? DEFAULT_SORT : raw).split(",");
		String field = parts[0].strip().toLowerCase(Locale.ROOT);
		boolean ascending = parts.length <= 1 || !parts[1].strip().equalsIgnoreCase("desc");
		if (field.equals("district")) {
			return new CrossTabSort(null, ascending);
		}
		Measure measure = Measure.byId(field).filter(wanted::contains)
				.orElseThrow(() -> badRequest("sort field must be 'district' or one of the requested measures "
						+ wanted.stream().map(Measure::id).toList()));
		return new CrossTabSort(measure, ascending);
	}

	private ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

}
