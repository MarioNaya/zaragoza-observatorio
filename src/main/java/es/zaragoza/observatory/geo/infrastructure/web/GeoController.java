package es.zaragoza.observatory.geo.infrastructure.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import es.zaragoza.observatory.geo.DistrictLocation;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.geo.GeoSources;
import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictLocator;
import es.zaragoza.observatory.geo.domain.DistrictRepository;
import es.zaragoza.observatory.geo.domain.PopulationRepository;
import es.zaragoza.observatory.geo.infrastructure.GeoProperties;
import es.zaragoza.observatory.geo.infrastructure.persistence.DistrictBoundaries;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.ApiItem;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.ApiList;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.DistrictDetailDto;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.DistrictDto;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.FeatureCollectionDto;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.FeatureDto;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.LocationDto;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.PopulationDto;
import es.zaragoza.observatory.geo.infrastructure.web.GeoDtos.Source;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;

/**
 * Territorio (SPEC.md §4.7): las 29 juntas con su padrón y la resolución de un punto a junta. Sin paginación
 * —son 29— pero con ordenación explícita y declarada (regla 8).
 */
@RestController
@RequestMapping("/api/v1/geo")
class GeoController {

	/** Ordenaciones admitidas del listado de juntas. */
	static final Map<String, Comparator<District>> SORT_FIELDS = Map.of(
			"id", Comparator.comparingInt(District::id),
			"name", Comparator.comparing(District::shortName, String.CASE_INSENSITIVE_ORDER),
			"padronId", Comparator.comparing(District::padronId, Comparator.nullsLast(Comparator.naturalOrder())));

	private final DistrictRepository districts;
	private final PopulationRepository population;
	private final DistrictLocator locator;
	private final DistrictBoundaries boundaries;
	private final Ingestion ingestion;
	private final Source source;

	GeoController(DistrictRepository districts, PopulationRepository population, DistrictLocator locator,
			DistrictBoundaries boundaries, Ingestion ingestion, GeoProperties properties) {
		this.districts = districts;
		this.population = population;
		this.locator = locator;
		this.boundaries = boundaries;
		this.ingestion = ingestion;
		this.source = new Source(GeoSources.DISTRICTS.key(), properties.districtsUrl().toString());
	}

	@GetMapping("/districts")
	ApiList<DistrictDto> districts(@RequestParam(defaultValue = "id,asc") String sort,
			@RequestParam(required = false) String kind) {
		var comparator = comparator(sort);
		List<DistrictDto> items = districts.findAll().stream()
				.filter(district -> kind == null || district.kind().name().equalsIgnoreCase(kind.strip()))
				.sorted(comparator)
				.map(district -> DistrictDto.of(district, population.findLatest(district.id()).orElse(null)))
				.toList();
		return new ApiList<>(source, ingestedAt(), GeoCaveats.DISTRICTS, items.size(), items);
	}

	@GetMapping("/districts/{id}")
	ApiItem<DistrictDetailDto> district(@PathVariable int id) {
		District district = districts.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no district with id " + id));
		List<PopulationDto> series = population.findByDistrict(id).stream().map(PopulationDto::of).toList();
		var detail = new DistrictDetailDto(
				DistrictDto.of(district, population.findLatest(id).orElse(null)), series);
		return new ApiItem<>(source, ingestedAt(), GeoCaveats.DISTRICTS, detail);
	}

	/**
	 * Los 29 contornos en GeoJSON, para pintarlos (ADR-020 §4). Es el mismo polígono que resuelve los puntos,
	 * sin simplificar, y con las mismas propiedades que el listado para poder casarlo con cualquier respuesta
	 * del cruce por {@code id}.
	 * <p>
	 * Se publica como {@code FeatureCollection} y no como un campo más de {@code /districts} porque son 373 KB
	 * de coordenadas que no cambian nunca: separarlo deja que el navegador lo pida una vez y lo cachee, mientras
	 * las cifras se repiten tantas veces como haga falta.
	 */
	@GetMapping(value = "/boundaries", produces = "application/geo+json")
	ResponseEntity<FeatureCollectionDto> boundaries() {
		Map<Integer, String> geometries = boundaries.findAll();
		List<FeatureDto> features = districts.findAll().stream()
				.filter(district -> geometries.containsKey(district.id()))
				.map(district -> FeatureDto.of(district, geometries.get(district.id()),
						population.findLatest(district.id()).orElse(null)))
				.toList();
		return ResponseEntity.ok()
				// El contorno oficial no cambia entre ingestas: que el navegador no lo vuelva a pedir.
				.cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePublic())
				.body(new FeatureCollectionDto(source, ingestedAt(), GeoCaveats.DISTRICTS, features.size(),
						features));
	}

	/**
	 * Resuelve un punto WGS84 a junta con {@code ST_Contains} (ADR-011). Es la misma operación que usa la
	 * ingesta de cualquier fuente territorial: se expone para poder comprobarla desde fuera.
	 */
	@GetMapping("/locate")
	ApiItem<LocationDto> locate(@RequestParam double lon, @RequestParam double lat) {
		GeoPoint point;
		try {
			point = new GeoPoint(lon, lat);
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
		}
		DistrictLocation location = locator.locate(point);
		String name = location.districtId() == null ? null
				: districts.findById(location.districtId()).map(District::shortName).orElse(null);
		return new ApiItem<>(source, ingestedAt(), GeoCaveats.LOCATE,
				LocationDto.of(lon, lat, location, name));
	}

	private Comparator<District> comparator(String sort) {
		String[] parts = (sort == null ? "id,asc" : sort).split(",");
		Comparator<District> field = SORT_FIELDS.get(parts[0].strip());
		if (field == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"sort field must be one of " + SORT_FIELDS.keySet().stream().sorted().toList());
		}
		boolean descending = parts.length > 1 && parts[1].strip().equalsIgnoreCase("desc");
		// Desempate estable por id, siempre (regla 8).
		Comparator<District> comparator = descending ? field.reversed() : field;
		return comparator.thenComparing(Comparator.comparingInt(District::id));
	}

	private Instant ingestedAt() {
		return ingestion.lastSuccessful(GeoSources.DISTRICTS).map(IngestionRunSummary::finishedAt).orElse(null);
	}

}
