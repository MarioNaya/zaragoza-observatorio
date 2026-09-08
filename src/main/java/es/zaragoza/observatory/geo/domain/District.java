package es.zaragoza.observatory.geo.domain;

import java.time.Instant;

/**
 * Una junta municipal o vecinal: la unidad territorial del observatorio (S0.4, ADR-011). No existe el barrio como
 * dato abierto, así que no existe aquí.
 * <p>
 * Lleva <b>dos numeraciones</b> porque la fuente publica dos y no coinciden (S2.1): {@code id} es el de la API
 * que sirve las geometrías ({@code distrito.id}, = {@code iddatosab} de los indicadores) e {@code padronId} es el
 * de los datasets de población ({@code idpadron}). Sin la segunda no se puede cruzar con SOCIO24.
 *
 * @param id {@code distrito.id} (1..30 con huecos)
 * @param padronId {@code idpadron}; {@code null} mientras no se haya leído el detalle de la junta
 * @param name título tal como lo publica la fuente, con su prefijo («Junta Municipal Delicias»)
 * @param kind municipal o vecinal, derivado del prefijo del título
 * @param boundary contorno en WGS84; {@code null} al leer del repositorio: la geometría vive en PostGIS y solo
 * viaja en la ingesta
 * @param firstSeenAt primera ingesta en la que apareció
 * @param lastSeenAt última ingesta en la que apareció
 */
public record District(int id, Integer padronId, String name, DistrictKind kind, Boundary boundary,
		Instant firstSeenAt, Instant lastSeenAt) {

	public District {
		if (id <= 0) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (padronId != null && padronId <= 0) {
			throw new IllegalArgumentException("padronId must be positive when present (district " + id + ")");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank (district " + id + ")");
		}
		if (kind == null) {
			throw new IllegalArgumentException("kind must not be null (district " + id + ")");
		}
		name = name.strip();
	}

	/** Nombre sin el prefijo «Junta Municipal»/«Junta Vecinal», para listados y etiquetas. */
	public String shortName() {
		return kind.stripPrefix(name);
	}

	public District seen(Instant firstSeenAt, Instant lastSeenAt) {
		return new District(id, padronId, name, kind, boundary, firstSeenAt, lastSeenAt);
	}

}
