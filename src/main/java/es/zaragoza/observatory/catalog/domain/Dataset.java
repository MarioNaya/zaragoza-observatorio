package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Ficha de un dataset del catálogo municipal (SPEC.md §4.6 «catalog», S0.1 recomendación 2). Todos los campos
 * existen en la respuesta de {@code catalogo.json}; ninguno se inventa. Las fechas del catálogo vienen sin zona
 * ({@code 2019-10-23T00:00:00}) y se conservan como hora local de Zaragoza (S0.5).
 *
 * @param sourceId {@code id} municipal de la ficha
 * @param title {@code title}
 * @param description {@code description_basic}
 * @param issued {@code issued}
 * @param declaredModified {@code modified}: modificación del dato declarada por el publicador; puede faltar
 * @param metadataUpdated {@code lastUpdated}: actualización de la ficha de metadatos, no del dato (S0.1)
 * @param declaredPeriodicity {@code accrualPeriodicity} tal cual ({@code P1Y}, {@code NEVER}, {@code IRREG},
 * {@code P0DT1S}…); puede faltar
 * @param periodicityDays días aproximados derivados de la periodicidad, o {@code null} si no es un periodo
 * evaluable ({@link Periodicity})
 * @param publicationStatus {@code status} del proyecto de publicación ({@code Finalizado}, {@code En proceso})
 * @param hasGeo {@code geo} ({@code S}/{@code N}); {@code null} si falta
 * @param open {@code abierto} ({@code S}/{@code N}); {@code null} si falta
 * @param explorable {@code explorable}: cargado en la plataforma de datos
 * @param apiTag tag del Swagger de la API al que apunta la distribución {@code application/api}, si la hay
 * @param distributions {@code formato[]}
 * @param firstSeenAt primera ingesta en la que apareció
 * @param lastSeenAt última ingesta en la que apareció (= {@code ingestedAt} de trazabilidad)
 */
public record Dataset(int sourceId, String title, String description, LocalDateTime issued,
		LocalDateTime declaredModified, LocalDateTime metadataUpdated, String declaredPeriodicity,
		Integer periodicityDays, String publicationStatus, Boolean hasGeo, Boolean open, boolean explorable,
		String apiTag, List<Distribution> distributions, Instant firstSeenAt, Instant lastSeenAt) {

	public Dataset {
		if (sourceId <= 0) {
			throw new IllegalArgumentException("sourceId must be positive");
		}
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title must not be blank (dataset " + sourceId + ")");
		}
		distributions = distributions == null ? List.of() : List.copyOf(distributions);
	}

	/** Copia con las marcas de ingesta actualizadas. */
	public Dataset seen(Instant firstSeenAt, Instant lastSeenAt) {
		return new Dataset(sourceId, title, description, issued, declaredModified, metadataUpdated,
				declaredPeriodicity, periodicityDays, publicationStatus, hasGeo, open, explorable, apiTag,
				distributions, Objects.requireNonNull(firstSeenAt), Objects.requireNonNull(lastSeenAt));
	}

	public boolean hasApiDistribution() {
		return distributions.stream().anyMatch(Distribution::isApi);
	}

	/**
	 * Una distribución de {@code formato[]}.
	 *
	 * @param sourceId {@code formato[].id}
	 * @param mediaType {@code mediaType} ({@code application/api}, {@code text/csv}, WFS…)
	 * @param accessUrl {@code accessURL}
	 * @param downloadUrl {@code downloadURL} (relativo a www.zaragoza.es en las API)
	 * @param title {@code title}
	 * @param wfsFeatureName {@code wfsFeatureName}: capa del servicio WFS (S1.1); solo en distribuciones WFS
	 */
	public record Distribution(Integer sourceId, String mediaType, String accessUrl, String downloadUrl,
			String title, String wfsFeatureName) {

		public static final String API_MEDIA_TYPE = "application/api";

		public boolean isApi() {
			return API_MEDIA_TYPE.equals(mediaType);
		}
	}

}
