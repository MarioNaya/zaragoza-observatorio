package es.zaragoza.observatory.ingestion;

import java.time.Duration;

/**
 * Contrato que implementa cada módulo de dominio para que {@code ingestion} traiga una fuente por él
 * (SPEC.md §4.5). El módulo declara qué traer ({@link #source()}), cada cuánto ({@link #interval()}) y qué hacer
 * con cada página ({@link #handle(RawPage)}: traducir con su adaptador anti-corrupción y persistir con upsert
 * idempotente). Los beans que implementan esta interfaz los descubre el planificador de {@code ingestion}.
 * <p>
 * {@code handle} se invoca página a página, fuera de cualquier transacción de {@code ingestion}; el módulo abre
 * la suya. Si lanza una excepción, la ejecución se marca como fallida y no se publica {@code DatasetIngested}.
 */
public interface IngestionJob {

	SourceDescriptor source();

	/** Intervalo mínimo entre dos ejecuciones con éxito (SPEC.md §5: diario por defecto, catálogo cada 6 h). */
	Duration interval();

	void handle(RawPage page);

	/** Nombre legible para logs y registros; por defecto la clave del dataset. */
	default String name() {
		return source().dataset().key();
	}

}
