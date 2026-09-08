package es.zaragoza.observatory.geo.infrastructure.web;

import java.time.Instant;
import java.util.List;

import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictLocation;
import es.zaragoza.observatory.geo.domain.PopulationRecord;

/** Cuerpos de respuesta del módulo {@code geo} (SPEC.md §4.7): datos de origen, sin índices derivados. */
final class GeoDtos {

	private GeoDtos() {
	}

	/** Procedencia del dato, igual que en el resto de la API: dataset de origen y URL consumida. */
	record Source(String dataset, String url) {
	}

	record ApiList<T>(Source source, Instant ingestedAt, List<String> caveats, int count, List<T> items) {
	}

	record ApiItem<T>(Source source, Instant ingestedAt, List<String> caveats, T item) {
	}

	/**
	 * Una junta. {@code id} es el de la API municipal y {@code padronId} el de los datasets de población: son
	 * dos numeraciones distintas y las dos hacen falta (S2.1).
	 */
	record DistrictDto(int id, Integer padronId, String name, String shortName, String kind, Instant firstSeenAt,
			Instant lastSeenAt, PopulationDto latestPopulation) {

		static DistrictDto of(District district, PopulationRecord latest) {
			return new DistrictDto(district.id(), district.padronId(), district.name(), district.shortName(),
					district.kind().name(), district.firstSeenAt(), district.lastSeenAt(), PopulationDto.of(latest));
		}
	}

	record DistrictDetailDto(DistrictDto district, List<PopulationDto> population) {
	}

	/**
	 * El padrón de un año. {@code densityPerKm2} es la única cifra compuesta y se da porque el denominador viaja
	 * al lado ({@code total} y {@code areaKm2}): quien la lea puede rehacerla o ignorarla (regla 6, regla 7).
	 */
	record PopulationDto(int year, Integer total, Integer spaniards, Integer foreigners, Integer under16,
			Integer under18, Integer households, Double areaKm2, Double densityPerKm2) {

		static PopulationDto of(PopulationRecord record) {
			if (record == null) {
				return null;
			}
			return new PopulationDto(record.year(), record.total(), record.spaniards(), record.foreigners(),
					record.under16(), record.under18(), record.households(), record.areaKm2(),
					record.densityPerKm2());
		}
	}

	/**
	 * Resultado de resolver un punto. {@code candidates} trae todas las juntas que lo contienen: con
	 * {@code status = AMBIGUOUS} son varias, porque los polígonos publicados se solapan (S2.1).
	 */
	record LocationDto(double lon, double lat, String status, Integer districtId, String districtName,
			List<Integer> candidates) {

		static LocationDto of(double lon, double lat, DistrictLocation location, String districtName) {
			return new LocationDto(lon, lat, location.status().name(), location.districtId(), districtName,
					location.candidates());
		}
	}

}
