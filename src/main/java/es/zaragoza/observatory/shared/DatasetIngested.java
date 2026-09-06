package es.zaragoza.observatory.shared;

import java.time.Instant;
import java.util.Objects;

/**
 * Evento base de integración (SPEC.md §4.5, paso 5): una ingesta de {@code dataset} ha terminado con éxito.
 * Lo publica {@code ingestion} al completar un {@code IngestionRun}; {@code catalog} lo escucha para el monitor
 * de frescura (paso 6). Se serializa en el registro de publicación de Spring Modulith (ADR-004), así que debe
 * seguir siendo un valor plano y estable.
 *
 * @param dataset dataset ingerido
 * @param run ejecución que lo produjo
 * @param records número de registros obtenidos de la fuente en esta ejecución (suma de todas las páginas)
 * @param ingestedAt instante en que terminó la ingesta
 */
public record DatasetIngested(DatasetRef dataset, IngestionRunId run, long records, Instant ingestedAt) {

	public DatasetIngested {
		Objects.requireNonNull(dataset, "dataset must not be null");
		Objects.requireNonNull(run, "run must not be null");
		Objects.requireNonNull(ingestedAt, "ingestedAt must not be null");
		if (records < 0) {
			throw new IllegalArgumentException("records must not be negative");
		}
	}

}
