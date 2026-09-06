package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Instantánea diaria de frescura de un dataset (SPEC.md §4.6). Dos ejes separados que nunca se mezclan (S0.1):
 * el <em>declarado</em> (ratio antigüedad de {@code modified} / periodicidad) y el <em>observado</em> (lo que la
 * distribución devuelve al preguntarle, con el método de S1.1). Cada eje se escribe por separado
 * ({@link #withDeclared} y {@link #withObservation}) y ninguno borra al otro.
 *
 * @param datasetSourceId {@code id} municipal del dataset
 * @param observedOn día de la instantánea (una por dataset y día: idempotente)
 * @param takenAt instante del cálculo declarado
 * @param declaredAgeDays días desde {@code modified}, o {@code null}
 * @param periodicityDays días del periodo declarado, o {@code null}
 * @param declaredRatio ratio, o {@code null} si no es evaluable
 * @param declared categoría según {@link FreshnessPolicy}
 * @param observedAt instante de la observación, o {@code null} si el día aún no se ha observado
 * @param observationMethod método aplicado o intentado ({@link ObservationMethod})
 * @param observedUrl URL consultada
 * @param observedLastChange último cambio observado en la distribución
 * @param observedRecords registros observados
 * @param observationDetail qué se midió (campo de fecha, ficheros consultados, capa)
 * @param observationError por qué no se pudo medir, o {@code null}
 */
public record FreshnessSnapshot(UUID id, int datasetSourceId, LocalDate observedOn, Instant takenAt,
		Integer declaredAgeDays, Integer periodicityDays, Double declaredRatio, DeclaredFreshness declared,
		Instant observedAt, ObservationMethod observationMethod, String observedUrl, Instant observedLastChange,
		Integer observedRecords, String observationDetail, String observationError) {

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
				null, null, null, null, null, null);
	}

	/** Recalcula el eje declarado conservando la observación del día. */
	public FreshnessSnapshot withDeclared(FreshnessPolicy.Evaluation evaluation, Instant takenAt) {
		return new FreshnessSnapshot(id, datasetSourceId, observedOn, Objects.requireNonNull(takenAt),
				evaluation.ageDays(), evaluation.periodicityDays(), evaluation.ratio(), evaluation.category(),
				observedAt, observationMethod, observedUrl, observedLastChange, observedRecords, observationDetail,
				observationError);
	}

	/** Escribe el eje observado conservando el declarado. */
	public FreshnessSnapshot withObservation(Observation observation) {
		Objects.requireNonNull(observation);
		return new FreshnessSnapshot(id, datasetSourceId, observedOn, takenAt, declaredAgeDays, periodicityDays,
				declaredRatio, declared, observation.observedAt(), observation.method(), observation.url(),
				observation.lastChange(), observation.records(), observation.detail(), observation.error());
	}

	public boolean hasObservation() {
		return observedAt != null;
	}

}
