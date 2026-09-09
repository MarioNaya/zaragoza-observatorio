package es.zaragoza.observatory.geo.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import es.zaragoza.observatory.geo.DistrictLocation;
import es.zaragoza.observatory.geo.DistrictNames;
import es.zaragoza.observatory.geo.DistrictPopulation;
import es.zaragoza.observatory.geo.DistrictSummary;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictLocator;
import es.zaragoza.observatory.geo.domain.DistrictRepository;
import es.zaragoza.observatory.geo.domain.PopulationRecord;
import es.zaragoza.observatory.geo.domain.PopulationRepository;

/**
 * Implementación de la superficie pública de {@code geo}: delega la resolución espacial en el puerto
 * {@link DistrictLocator} (PostGIS, ADR-011) y construye el índice de nombres con las juntas ingeridas.
 */
public class GeoService implements Geo {

	private final DistrictLocator locator;
	private final DistrictRepository districts;
	private final PopulationRepository population;

	public GeoService(DistrictLocator locator, DistrictRepository districts, PopulationRepository population) {
		this.locator = Objects.requireNonNull(locator);
		this.districts = Objects.requireNonNull(districts);
		this.population = Objects.requireNonNull(population);
	}

	@Override
	public DistrictLocation locate(GeoPoint point) {
		return locator.locate(point);
	}

	@Override
	public List<DistrictLocation> locateAll(List<GeoPoint> points) {
		return locator.locateAll(points);
	}

	@Override
	public DistrictNames districtNames() {
		var byId = new LinkedHashMap<Integer, String>();
		for (District district : districts.findAll()) {
			byId.put(district.id(), district.name());
		}
		return DistrictNames.of(byId);
	}

	@Override
	public List<DistrictSummary> districts() {
		return districts.findAll().stream().map(this::summarize).toList();
	}

	@Override
	public List<DistrictPopulation> populations() {
		return population.findAll().stream()
				.filter(record -> record.total() != null)
				.map(record -> new DistrictPopulation(record.districtId(), record.year(), record.total()))
				.toList();
	}

	private DistrictSummary summarize(District district) {
		PopulationRecord latest = population.findLatest(district.id()).filter(r -> r.total() != null).orElse(null);
		return new DistrictSummary(district.id(), district.name(), district.shortName(), district.padronId(),
				latest == null ? null : latest.total(), latest == null ? null : latest.year());
	}

}
