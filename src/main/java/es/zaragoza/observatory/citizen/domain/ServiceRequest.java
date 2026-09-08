package es.zaragoza.observatory.citizen.domain;

import java.time.Duration;
import java.time.Instant;

import es.zaragoza.observatory.geo.GeoPoint;

/**
 * Una queja o sugerencia del listado de sede (S0.3, S2.2). Lo que no lleva es deliberado:
 * <ul>
 * <li><b>sin texto libre</b> ({@code title}, {@code description}, {@code service_notice}): el origen lo publica
 * sin anonimizar y la ingesta no lo pide (ADR-012);</li>
 * <li><b>sin dirección textual</b>: ADR-011 §2 prohíbe geocodificar por dirección, así que no tendría uso;</li>
 * <li><b>sin canal de entrada</b>: no existe en los datos (S0.3).</li>
 * </ul>
 * La fecha de cierre no es un campo aparte: el origen informa {@code updated_datetime} al cerrar (S0.3), así que
 * se guarda tal cual y {@link #closedAt()} lo interpreta solo cuando el estado es {@code CLOSED}. Guardarlo
 * siempre es lo que permite que la marca de agua de la ingesta de cierres sea exacta.
 *
 * @param sourceId {@code service_request_id} del origen
 * @param status estado publicado
 * @param serviceCode código de servicio, tal cual (la taxonomía es heterogénea: {@code 250}, {@code 97550336})
 * @param serviceName nombre del servicio, {@code null} si el origen no lo trae
 * @param requestedAt alta, convertida a instante desde hora local de Zaragoza (S0.5)
 * @param updatedAt {@code updated_datetime} tal cual, {@code null} si no viene
 * @param point punto en WGS84, {@code null} en la mayoría de los registros (16–45 % lo traen según el año, S2.2)
 * @param district asignación territorial: la resuelta y la declarada, nunca fundidas (ADR-011 §3)
 * @param firstSeenAt primera ingesta en la que apareció
 * @param lastSeenAt última ingesta en la que apareció
 */
public record ServiceRequest(long sourceId, ServiceRequestStatus status, String serviceCode, String serviceName,
		Instant requestedAt, Instant updatedAt, GeoPoint point, DistrictAssignment district, Instant firstSeenAt,
		Instant lastSeenAt) {

	public ServiceRequest {
		if (sourceId <= 0) {
			throw new IllegalArgumentException("sourceId must be positive");
		}
		if (status == null) {
			throw new IllegalArgumentException("status must not be null (request " + sourceId + ")");
		}
		if (serviceCode == null || serviceCode.isBlank()) {
			throw new IllegalArgumentException("serviceCode must not be blank (request " + sourceId + ")");
		}
		if (requestedAt == null) {
			throw new IllegalArgumentException("requestedAt must not be null (request " + sourceId + ")");
		}
		if (district == null) {
			throw new IllegalArgumentException("district must not be null (request " + sourceId + ")");
		}
		if ((point == null) != (district.status() == Assignment.NO_POINT)) {
			throw new IllegalArgumentException(
					"a request has a point exactly when its assignment is not NO_POINT (request " + sourceId + ")");
		}
		serviceCode = serviceCode.strip();
		serviceName = serviceName == null || serviceName.isBlank() ? null : serviceName.strip();
	}

	/**
	 * Fecha de cierre: {@code updated_datetime} <b>solo</b> si la queja está cerrada. En las abiertas es
	 * {@code null} aunque el origen traiga la marca, porque una actualización no es un cierre.
	 */
	public Instant closedAt() {
		return status == ServiceRequestStatus.CLOSED ? updatedAt : null;
	}

	/**
	 * Tiempo de respuesta, o {@code null} si sigue abierta o falta la fecha. Nunca se estima el de las abiertas:
	 * tienen censura por la derecha y una media que las ignore en silencio es una conclusión inventada (regla 6).
	 */
	public Duration responseTime() {
		Instant closed = closedAt();
		if (closed == null || closed.isBefore(requestedAt)) {
			return null;
		}
		return Duration.between(requestedAt, closed);
	}

	public ServiceRequest seen(Instant firstSeenAt, Instant lastSeenAt) {
		return new ServiceRequest(sourceId, status, serviceCode, serviceName, requestedAt, updatedAt, point, district,
				firstSeenAt, lastSeenAt);
	}

	/** El mismo registro con su asignación territorial ya resuelta. */
	public ServiceRequest assignedTo(DistrictAssignment assignment) {
		return new ServiceRequest(sourceId, status, serviceCode, serviceName, requestedAt, updatedAt, point,
				assignment, firstSeenAt, lastSeenAt);
	}

}
