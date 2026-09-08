package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictKind;

/**
 * Tabla {@code geo_district} (V008). <b>No mapea la columna {@code boundary}</b>: es una geometría de PostGIS y
 * se escribe y consulta con SQL nativo ({@code ST_GeomFromGeoJSON}, {@code ST_Contains}) desde
 * {@link JpaDistrictRepository} y {@link PostgisDistrictLocator}. Así el módulo no necesita Hibernate Spatial ni
 * JTS para lo único que hace con la geometría, que es dejar que la base de datos resuelva la pertenencia.
 * <p>
 * Que la columna no esté mapeada no molesta a {@code ddl-auto=validate}: la validación comprueba que existan las
 * columnas mapeadas, no al revés (ADR-004).
 */
@Entity
@Table(name = "geo_district")
class DistrictEntity {

	@Id
	@Column(name = "id")
	private Integer id;

	@Column(name = "padron_id")
	private Integer padronId;

	@Column(name = "name", nullable = false, columnDefinition = "text")
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, columnDefinition = "text")
	private DistrictKind kind;

	@Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamptz")
	private Instant firstSeenAt;

	@Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
	private Instant lastSeenAt;

	protected DistrictEntity() {
	}

	/** El contorno no viaja de vuelta: vive en PostGIS y solo se usa para resolver puntos (ADR-011). */
	District toDomain() {
		return new District(id, padronId, name, kind, null, firstSeenAt, lastSeenAt);
	}

	Integer getId() {
		return id;
	}

	Integer getPadronId() {
		return padronId;
	}

	void setPadronId(Integer padronId) {
		this.padronId = padronId;
	}

}
