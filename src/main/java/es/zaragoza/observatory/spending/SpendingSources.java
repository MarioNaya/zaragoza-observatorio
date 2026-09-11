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

	/**
	 * Concesiones de subvenciones: {@code sede/servicio/ayuda-subvencion/resolucion.json} (S3.3, ADR-018).
	 * <p>
	 * Aquí <b>sí</b> hay una referencia por recurso, al revés que en la contratación y el presupuesto, y por una
	 * razón concreta: no son la misma fuente vista de varias formas, sino cuatro recursos con censos, tamaños y
	 * frescuras distintos, que se ingieren por separado y sin esperarse.
	 */
	public static final DatasetRef GRANTS = DatasetRef.of(Sources.SEDE, "ayuda-subvencion/resolucion");

	/** Convocatorias: {@code sede/servicio/ayuda-subvencion/convocatoria.json}. */
	public static final DatasetRef GRANT_CALLS = DatasetRef.of(Sources.SEDE, "ayuda-subvencion/convocatoria");

	/** Directorio de beneficiarios: {@code sede/servicio/ayuda-subvencion-v2/organization.json}. */
	public static final DatasetRef GRANT_BENEFICIARIES = DatasetRef.of(Sources.SEDE,
			"ayuda-subvencion-v2/organization");

	/**
	 * Enlace concesión → beneficiario: {@code sede/servicio/ayuda-subvencion-v2/concesion.json}.
	 * <p>
	 * De este recurso <b>solo</b> se leen tres campos. Es un subconjunto estricto del censo de la v1 (S3.3 §4) y
	 * no aporta ninguna concesión propia: lo único que tiene y la v1 no es el identificador del beneficiario.
	 */
	public static final DatasetRef GRANT_LINKS = DatasetRef.of(Sources.SEDE, "ayuda-subvencion-v2/concesion");

	private SpendingSources() {
	}

}
