package es.zaragoza.observatory.ingestion;

import java.util.List;
import java.util.Optional;

import es.zaragoza.observatory.shared.DatasetRef;

/**
 * Superficie pública del módulo {@code ingestion} para el resto de módulos: ejecutar un job bajo demanda y consultar
 * el registro de ejecuciones. La ejecución periódica la hace el planificador del propio módulo.
 */
public interface Ingestion {

	/** Ejecuta el job completo (todas las páginas) de forma síncrona y devuelve el resultado, con éxito o fallo. */
	IngestionRunSummary run(IngestionJob job);

	Optional<IngestionRunSummary> lastSuccessful(DatasetRef dataset);

	/** Últimas ejecuciones del dataset, la más reciente primero. */
	List<IngestionRunSummary> history(DatasetRef dataset, int limit);

}
