package es.zaragoza.observatory.spending;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;

/** Referencias de las fuentes que ingiere el módulo {@code spending} (S3.1, ADR-017). */
public final class SpendingSources {

	/**
	 * Censo de procesos de contratación: {@code sede/servicio/contratacion-publica/ocds/contracting-process.json}.
	 * <p>
	 * <b>Una sola referencia para las dos peticiones del censo</b> (el listado ampliado y el documentado) y para
	 * el detalle, porque son la misma fuente vista de tres formas y su frescura es una sola. El detalle no es un
	 * {@code IngestionJob}: son 8.001 peticiones y las hace el planificador propio del módulo (ADR-017 §5).
	 */
	public static final DatasetRef PROCESSES = DatasetRef.of(Sources.OCDS, "contracting-process");

	/**
	 * Censo de instantáneas del presupuesto de gastos:
	 * {@code sede/servicio/presupuesto/gasto-corriente/fecha.json} (S3.2).
	 * <p>
	 * <b>Una sola referencia para el censo y para las instantáneas</b>, por lo mismo que en la contratación: son
	 * la misma fuente vista de dos formas y su frescura es una sola. Las 140 instantáneas no son un
	 * {@code IngestionJob} —son 396 peticiones— y las lee el planificador propio del módulo.
	 * <p>
	 * El identificador es {@code presupuesto/gasto-corriente} y no {@code presupuesto} a secas porque el mismo
	 * servicio publica el presupuesto de <b>ingresos</b>, que es otra cosa y no se ingiere (ADR-003).
	 */
	public static final DatasetRef BUDGET = DatasetRef.of(Sources.SEDE, "presupuesto/gasto-corriente");

	private SpendingSources() {
	}

}
