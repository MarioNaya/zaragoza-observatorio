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

	private SpendingSources() {
	}

}
