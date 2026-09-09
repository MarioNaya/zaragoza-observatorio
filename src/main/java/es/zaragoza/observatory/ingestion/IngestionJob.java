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

	/**
	 * Si la página cruda se guarda en {@code raw_payload} para depuración y reprocesado (SPEC.md §4.5, con la
	 * retención de {@code zaragoza.ingestion.raw-retention}). Por defecto sí.
	 * <p>
	 * Un módulo lo desactiva cuando la respuesta trae <b>datos personales que no se pueden dejar de descargar</b>.
	 * Es el caso de {@code urban}: la fuente no admite proyección sin romper los registros anidados, así que el
	 * texto libre llega igual, y guardarlo catorce días en una tabla sería conservar justo lo que ADR-016 §3
	 * decidió no tener. En {@code citizen} no hizo falta porque allí el texto ni se pide (ADR-012).
	 * <p>
	 * Lo que se pierde al desactivarlo es poder reprocesar una página sin volver a pedirla. Es un precio
	 * consciente, y quien lo pague debe decir aquí por qué.
	 */
	default boolean keepsRawPayload() {
		return true;
	}

}
