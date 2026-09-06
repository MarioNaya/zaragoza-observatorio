package es.zaragoza.observatory.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.catalog.domain.ApiEndpointRepository;

/**
 * Registra el inventario de endpoints traducido del Swagger (S1.2): sincronización idempotente en una sola
 * transacción, porque el documento llega entero en una página (regla 5).
 */
public class RegisterApiEndpoints {

	private final ApiEndpointRepository endpoints;

	public RegisterApiEndpoints(ApiEndpointRepository endpoints) {
		this.endpoints = Objects.requireNonNull(endpoints);
	}

	@Transactional
	public int register(List<ApiEndpoint> document, Instant seenAt) {
		return endpoints.replaceAll(document, seenAt);
	}

}
