package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;

/** Tabla {@code catalog_api_endpoint} (V006): una fila por operación y tag del Swagger de la API (S1.2). */
@Entity
@Table(name = "catalog_api_endpoint")
class ApiEndpointEntity {

	@Id
	private UUID id;

	@Column(name = "tag", nullable = false, columnDefinition = "text")
	private String tag;

	@Column(name = "method", nullable = false, columnDefinition = "text")
	private String method;

	@Column(name = "path", nullable = false, columnDefinition = "text")
	private String path;

	@Column(name = "url", nullable = false, columnDefinition = "text")
	private String url;

	@Column(name = "summary", columnDefinition = "text")
	private String summary;

	@Column(name = "ordinal", nullable = false)
	private int ordinal;

	@Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamptz")
	private Instant firstSeenAt;

	@Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
	private Instant lastSeenAt;

	protected ApiEndpointEntity() {
	}

	static ApiEndpointEntity insert(ApiEndpoint endpoint, Instant seenAt) {
		var entity = new ApiEndpointEntity();
		entity.id = UUID.randomUUID();
		entity.tag = endpoint.tag();
		entity.method = endpoint.method();
		entity.path = endpoint.path();
		entity.firstSeenAt = seenAt;
		entity.apply(endpoint, seenAt);
		return entity;
	}

	/** Copia lo que no forma parte de la clave y actualiza {@code lastSeenAt}; conserva la primera aparición. */
	void apply(ApiEndpoint endpoint, Instant seenAt) {
		url = endpoint.url();
		summary = endpoint.summary();
		ordinal = endpoint.ordinal();
		lastSeenAt = seenAt;
	}

	String key() {
		return tag + " " + method + " " + path;
	}

	ApiEndpoint toDomain() {
		return new ApiEndpoint(tag, method, path, url, summary, ordinal, firstSeenAt, lastSeenAt);
	}

}
