package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Instantánea diaria de frescura de un dataset (SPEC.md §4.6). Dos ejes separados que nunca se mezclan (S0.1):
 * el <em>declarado</em> (ratio antigüedad de {@code modified} / periodicidad) y el <em>observado</em> (último
 * cambio detectado en la distribución). El eje observado queda reservado hasta que un spike confirme qué
 * devuelven las distribuciones (cabeceras de fichero, fechas máximas en API); mientras tanto sus campos son
 * {@code null} y la API lo declara en {@code caveats}.
 *
 * @param datasetSourceId {@code id} municipal del dataset
 * @param observedOn día de la instantánea (una por dataset y día: idempotente)
 * @param takenAt instante de cálculo
 * @param declaredAgeDays días desde {@code modified}, o {@code null}
 * @param periodicityDays días del periodo declarado, o {@code null}
 * @param declaredRatio ratio, o {@code null} si no es evaluable
 * @param declared categoría según {@link FreshnessPolicy}
 * @param observedLastChange último cambio observado en la distribución (reservado)
 * @param observedRecords registros muestreados (reservado)
 * @param observationMethod método de observación (reservado)
 */
public record FreshnessSnapshot(UUID id, int datasetSourceId, LocalDate observedOn, Instant takenAt,
		Integer declaredAgeDays, Integer periodicityDays, Double declaredRatio, DeclaredFreshness declared,
		Instant observedLastChange, Integer observedRecords, String observationMethod) {

	public FreshnessSnapshot {
		Objects.requireNonNull(id);
		Objects.requireNonNull(observedOn);
		Objects.requireNonNull(takenAt);
		Objects.requireNonNull(declared);
	}

	public static FreshnessSnapshot declaredOnly(Dataset dataset, LocalDate observedOn, Instant takenAt,
			FreshnessPolicy.Evaluation evaluation) {
		return new FreshnessSnapshot(UUID.randomUUID(), dataset.sourceId(), observedOn, takenAt,
				evaluation.ageDays(), evaluation.periodicityDays(), evaluation.ratio(), evaluation.category(), null,
				null, null);
	}

}
