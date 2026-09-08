package es.zaragoza.observatory.citizen.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import es.zaragoza.observatory.citizen.domain.AggregationBucket;
import es.zaragoza.observatory.citizen.domain.AssignmentCounts;
import es.zaragoza.observatory.citizen.domain.ServiceRequest;

/** Cuerpos de respuesta del módulo {@code citizen} (SPEC.md §4.7). */
final class CitizenDtos {

	private CitizenDtos() {
	}

	record Source(String dataset, String url) {
	}

	record ApiPage<T>(Source source, Instant ingestedAt, List<String> caveats, long total, int page, int size,
			List<T> items) {
	}

	record ApiItem<T>(Source source, Instant ingestedAt, List<String> caveats, T item) {
	}

	/**
	 * Una queja. <b>Sin texto</b> (ADR-012). {@code districtId} es la junta resuelta por geometría y
	 * {@code districtDeclared} lo que dice el origen: se publican los dos y no se funden (ADR-011 §3).
	 *
	 * @param responseHours horas entre alta y cierre, {@code null} si sigue abierta
	 */
	record ServiceRequestDto(long id, String status, String serviceCode, String serviceName, Instant requestedAt,
			Instant closedAt, Double responseHours, Double lon, Double lat, String assignment, Integer districtId,
			String districtName, String districtDeclared, Integer districtDeclaredId, Instant firstSeenAt,
			Instant lastSeenAt) {

		static ServiceRequestDto of(ServiceRequest request, Map<Integer, String> districtNames) {
			var district = request.district();
			var responseTime = request.responseTime();
			return new ServiceRequestDto(request.sourceId(), request.status().name(), request.serviceCode(),
					request.serviceName(), request.requestedAt(), request.closedAt(),
					responseTime == null ? null : responseTime.toMillis() / 3_600_000.0,
					request.point() == null ? null : request.point().lon(),
					request.point() == null ? null : request.point().lat(), district.status().name(),
					district.districtId(), districtNames.get(district.districtId()), district.declaredName(),
					district.declaredDistrictId(), request.firstSeenAt(), request.lastSeenAt());
		}
	}

	/**
	 * Un grupo de una agregación. Solo cifras y su denominador: ni índices compuestos ni etiquetas
	 * interpretativas (regla 6).
	 *
	 * @param population padrón de la junta, {@code null} fuera del eje territorial o si no hay dato
	 * @param populationYear año de ese padrón: la serie no es continua, falta 2023 (S2.1)
	 * @param perThousandInhabitants quejas por mil habitantes, {@code null} sin denominador. Se da porque el
	 * denominador viaja al lado y quien lea puede rehacerlo o ignorarlo
	 * @param pointCoverage proporción del grupo que tiene punto: sin esto, comparar dos grupos es comparar dos
	 * coberturas distintas
	 */
	record BucketDto(String key, String label, long total, long closed, long open, long withPoint,
			Double pointCoverage, Double medianResponseHours, Integer population, Integer populationYear,
			Double perThousandInhabitants) {

		static BucketDto of(AggregationBucket bucket, String label, Integer population, Integer populationYear) {
			double coverage = bucket.total() == 0 ? 0 : (double) bucket.withPoint() / bucket.total();
			Double perThousand = population == null || population == 0 ? null
					: bucket.total() * 1000.0 / population;
			return new BucketDto(bucket.key(), label != null ? label : bucket.label(), bucket.total(),
					bucket.closed(), bucket.total() - bucket.closed(), bucket.withPoint(), coverage,
					bucket.medianResponseHours(), population, populationYear, perThousand);
		}
	}

	/**
	 * El resultado de una agregación con lo que la regla 7 exige al lado: el reparto por estado de asignación y
	 * el total sin asignar. Un mapa por junta que no diga cuántas quejas no tienen punto es una mentira por
	 * omisión.
	 */
	record AggregationDto(String by, List<BucketDto> buckets, AssignmentDto assignment, long matched,
			long unassigned) {
	}

	/**
	 * Reparto por estado de asignación territorial y contraste con la junta declarada por el origen.
	 *
	 * @param declaredAgrees registros donde la junta declarada coincide con la resuelta
	 * @param declaredDisagrees registros donde discrepan (dato sobre la fuente, no error que se corrija)
	 * @param declaredOnly registros sin punto pero con junta declarada que casa: <b>no</b> se usan para asignar
	 * @param declaredUnmatched nombres declarados que no casan con ninguna junta oficial
	 */
	record AssignmentDto(Map<String, Long> byAssignment, long declaredAgrees, long declaredDisagrees,
			long declaredOnly, long declaredUnmatched) {

		static AssignmentDto of(AssignmentCounts counts) {
			var map = new java.util.LinkedHashMap<String, Long>();
			counts.byAssignment().forEach((assignment, n) -> map.put(assignment.name(), n));
			return new AssignmentDto(map, counts.declaredAgrees(), counts.declaredDisagrees(), counts.declaredOnly(),
					counts.declaredUnmatched());
		}
	}

	/**
	 * Resumen del módulo: cuánto hay ingerido, de cuándo a cuándo y cómo se reparte territorialmente. No dice
	 * qué porcentaje del origen se ha ingerido porque el origen no publica su total: hay que sondearlo, y una
	 * cifra copiada de un spike envejece sin que nadie lo note (S2.2).
	 */
	record SummaryDto(long total, Instant earliestRequestedAt, Instant latestRequestedAt, Instant latestUpdatedAt,
			Map<String, Long> byStatus, AssignmentDto assignment) {
	}

}
