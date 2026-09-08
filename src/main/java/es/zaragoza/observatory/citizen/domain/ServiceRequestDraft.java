package es.zaragoza.observatory.citizen.domain;

import java.time.Instant;

import es.zaragoza.observatory.geo.GeoPoint;

/**
 * Una queja recién traducida del JSON, <b>antes</b> de resolver su territorio. Existe para que el traductor no
 * tenga que preguntar a {@code geo} registro a registro: la resolución es un {@code JOIN} espacial por lote
 * (ADR-011), así que primero se traduce la página entera y después se resuelve de una vez.
 *
 * @param declaredDistrict nombre de junta que trae el origen, tal cual y sin normalizar
 */
public record ServiceRequestDraft(long sourceId, ServiceRequestStatus status, String serviceCode, String serviceName,
		Instant requestedAt, Instant updatedAt, GeoPoint point, String declaredDistrict) {
}
