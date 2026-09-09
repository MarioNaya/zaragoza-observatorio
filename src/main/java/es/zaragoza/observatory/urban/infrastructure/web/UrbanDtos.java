package es.zaragoza.observatory.urban.infrastructure.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.urban.domain.AggregationBucket;
import es.zaragoza.observatory.urban.domain.Licence;
import es.zaragoza.observatory.urban.domain.LicensedPremises;
import es.zaragoza.observatory.urban.domain.YearCoverage;

/** Cuerpos de respuesta del módulo {@code urban} (SPEC.md §4.7). */
final class UrbanDtos {

	private UrbanDtos() {
	}

	record Source(String dataset, String url) {
	}

	record ApiPage<T>(Source source, Instant ingestedAt, List<String> caveats, long total, int page, int size,
			List<T> items) {
	}

	record ApiItem<T>(Source source, Instant ingestedAt, List<String> caveats, T item) {
	}

	/**
	 * Un local con licencia. <b>Sin texto libre de ninguna clase</b> y <b>sin junta declarada</b>, porque el
	 * origen no publica ninguna (ADR-016 §3 y §5).
	 *
	 * @param statusCode {@code estado} del origen, sin traducir: no hay taxonomía que lo describa
	 * @param deregisteredAt fecha de baja del origen. No significa «cerrado»: ver los caveats
	 */
	record PremisesDto(int id, String iaeCode, String iaeTitle, Integer iaeSection, Integer iaeGroup,
			int statusCode, String portalCode, String saturatedZone, Instant createdAt, Instant updatedAt,
			Instant deregisteredAt, Double lon, Double lat, String assignment, Integer districtId,
			String districtName, List<LicenceDto> licences, Instant firstSeenAt, Instant lastSeenAt) {

		static PremisesDto of(LicensedPremises premises, Map<Integer, String> districtNames) {
			var activity = premises.activity();
			return new PremisesDto(premises.sourceId(), activity.code(), activity.title(), activity.section(),
					activity.group(), premises.statusCode(), premises.portalCode(), premises.saturatedZone(),
					premises.createdAt(), premises.updatedAt(), premises.deregisteredAt(),
					premises.point() == null ? null : premises.point().lon(),
					premises.point() == null ? null : premises.point().lat(), premises.assignment().name(),
					premises.districtId(), districtNames.get(premises.districtId()),
					premises.licences().stream().map(LicenceDto::of).toList(), premises.firstSeenAt(),
					premises.lastSeenAt());
		}
	}

	/** Una licencia del local. Su identidad dentro del local es {@code (year, fileNumber)} (S2.4 §7). */
	record LicenceDto(int year, long fileNumber, Integer order, int typeId, String typeName, LocalDate resolvedOn,
			Integer resolutionCode, Instant deregisteredAt) {

		static LicenceDto of(Licence licence) {
			return new LicenceDto(licence.year(), licence.fileNumber(), licence.displayOrder(), licence.typeId(),
					licence.typeName(), licence.resolvedOn(), licence.resolutionCode(), licence.deregisteredAt());
		}
	}

	/**
	 * Un grupo de una agregación. Solo cifras y su denominador: ni índices compuestos ni etiquetas
	 * interpretativas (regla 6).
	 *
	 * @param total registros del grupo en la unidad del eje, que la respuesta declara en {@code unit}
	 * @param premises locales distintos del grupo
	 * @param licences licencias del grupo
	 * @param pointCoverage proporción de los locales del grupo que tiene punto
	 * @param deregistered registros con fecha de baja. No son «cierres»: ver los caveats
	 * @param population padrón de la junta en el año del grupo, {@code null} fuera del eje territorial o si el
	 * padrón no tiene ese año
	 * @param perThousandInhabitants registros por mil habitantes, {@code null} sin denominador
	 */
	record BucketDto(String key, String label, Integer year, long total, long premises, long licences,
			long withPoint, Double pointCoverage, long deregistered, Integer population, Integer populationYear,
			Double perThousandInhabitants) {

		static BucketDto of(AggregationBucket bucket, String label, Integer population, Integer populationYear) {
			double coverage = bucket.premises() == 0 ? 0 : (double) bucket.withPoint() / bucket.premises();
			Double perThousand = population == null || population == 0 ? null
					: bucket.total() * 1000.0 / population;
			return new BucketDto(bucket.key(), label != null ? label : bucket.label(), bucket.year(),
					bucket.total(), bucket.premises(), bucket.licences(), bucket.withPoint(), coverage,
					bucket.deregistered(), population, populationYear, perThousand);
		}
	}

	/**
	 * El resultado de una agregación con lo que la regla 7 exige al lado: qué unidad cuenta, el reparto por
	 * estado de asignación y el total sin asignar.
	 */
	record AggregationDto(String by, String unit, List<BucketDto> buckets, List<YearCoverageDto> coverageByYear,
			Map<String, Long> assignment, long matched, long unassigned) {
	}

	/** Cobertura de punto de un año entero de licencias, sobre todos sus locales y no solo sobre los situados. */
	record YearCoverageDto(int year, long total, long withPoint, long assigned, double pointCoverage) {

		static YearCoverageDto of(YearCoverage coverage) {
			return new YearCoverageDto(coverage.year(), coverage.total(), coverage.withPoint(), coverage.assigned(),
					coverage.pointCoverage());
		}
	}

	/**
	 * Resumen del módulo: cuánto hay ingerido, de cuándo a cuándo y cómo se reparte territorialmente. No dice
	 * qué porcentaje del origen se ha ingerido porque una cifra copiada de un spike envejece sin que nadie lo
	 * note; el origen sí publica su total, y el que se ve aquí es el que se ha guardado.
	 */
	record SummaryDto(long premises, long licences, Instant earliestCreatedAt, Instant latestUpdatedAt,
			Map<String, Long> byStatusCode, Map<String, Long> assignment) {
	}

	/** Reparto por estado de asignación con todos los estados presentes, aunque valgan 0 (regla 7). */
	static Map<String, Long> assignmentMap(Map<Assignment, Long> counts) {
		var map = new LinkedHashMap<String, Long>();
		for (Assignment assignment : Assignment.values()) {
			map.put(assignment.name(), counts.getOrDefault(assignment, 0L));
		}
		return map;
	}

}
